package com.aiinvestor.ai;

import com.aiinvestor.ai.AnalysisOutcome.Status;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Enums.Decision;
import com.aiinvestor.persistence.AiRecordRepository;
import com.aiinvestor.persistence.AiRecordRepository.NewRecord;
import com.aiinvestor.persistence.Db;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs one analysis attempt end to end and ALWAYS persists an ai_analysis_records row (success, failure, skip, reuse).
 * Raw model text is stored separately from the validated parse. A non-VALID outcome must be treated as NO_TRADE by callers.
 */
@Service
public class AiAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);

    private final AiAnalyst analyst;
    private final AiRecordRepository records;
    private final PromptBuilder prompts;
    private final AiResponseValidator validator;
    private final AppProperties.Ai cfg;
    private final ObjectMapper mapper;
    private final Map<String, AtomicInteger> runCalls = new ConcurrentHashMap<>();
    private volatile java.time.Instant lastOk;
    private volatile java.time.Instant lastFailure;
    private volatile String lastFailureMessage;

    public record Health(String analyst, String model, boolean configured, java.time.Instant lastSuccess,
                         java.time.Instant lastFailure, String lastFailureMessage) {}

    public Health health() { return new Health(analyst.name(), analyst.modelId(), analyst.isConfigured(), lastOk, lastFailure, lastFailureMessage); }

    public AiAnalysisService(AiAnalyst analyst, AiRecordRepository records, PromptBuilder prompts,
                             AiResponseValidator validator, AppProperties props, ObjectMapper mapper) {
        this.analyst = analyst;
        this.records = records;
        this.prompts = prompts;
        this.validator = validator;
        this.cfg = props.ai();
        this.mapper = mapper;
    }

    public AiAnalyst analyst() { return analyst; }

    public boolean enabled() { return cfg.enabled(); }

    /** @param maxCallsForRun per-run cap on LIVE API calls (backtests); paper runs also obey the daily cap */
    public AnalysisOutcome analyze(AnalysisRequest req, boolean isBacktest, int maxCallsForRun) {
        String system = prompts.systemPrompt(cfg.promptVersion());
        String user = prompts.userPrompt(req);
        String hash = sha256(cfg.promptVersion() + "|" + analyst.modelId() + "|" + system + "|" + user);
        String requestJson = requestJson(user);
        String id = Db.newId();

        if (!cfg.enabled()) return persist(id, req, Status.SKIPPED, null, null, null, List.of("AI analysis disabled by configuration"), null, null, null, hash, requestJson, null);

        // identical prompt already answered successfully by the same analyst/model → reuse (reproducible re-runs, no cost)
        if (cfg.reuseIdenticalPrompts()) {
            Optional<Map<String, Object>> prior = records.findReusable(hash, analyst.name(), analyst.modelId(), cfg.promptVersion());
            if (prior.isPresent()) {
                String raw = (String) prior.get().get("raw_response");
                AiResponseValidator.Validated v = validator.validate(raw, req);
                if (v.valid()) {
                    return persist(id, req, Status.VALID, v.assessment(), raw, v.parsedJson(), List.of(), 0L,
                            toLong(prior.get().get("input_tokens")), toLong(prior.get().get("output_tokens")), hash, requestJson,
                            (String) prior.get().get("id"));
                }
            }
        }

        // budgets only constrain real (billable) analyst calls
        if (analyst.name().equals("CLAUDE")) {
            int used = runCalls.computeIfAbsent(req.runId(), k -> new AtomicInteger()).get();
            if (isBacktest && used >= maxCallsForRun) {
                return persist(id, req, Status.SKIPPED, null, null, null, List.of("backtest AI call budget exhausted (" + maxCallsForRun + ")"), null, null, null, hash, requestJson, null);
            }
            if (!isBacktest && records.countLiveCallsOn(LocalDate.now(ZoneOffset.UTC), req.runMode()) >= cfg.maxCallsPerDay()) {
                return persist(id, req, Status.SKIPPED, null, null, null, List.of("daily AI call budget exhausted (" + cfg.maxCallsPerDay() + ")"), null, null, null, hash, requestJson, null);
            }
        }
        if (!analyst.isConfigured()) {
            return persist(id, req, Status.ERROR, null, null, null, List.of(analyst.name() + " is not configured (missing credentials)"), null, null, null, hash, requestJson, null);
        }

        runCalls.computeIfAbsent(req.runId(), k -> new AtomicInteger()).incrementAndGet();
        try {
            ClaudeGateway.RawResult raw = analyst.complete(req, system, user);
            AiResponseValidator.Validated v = validator.validate(raw.text(), req);
            if (v.valid()) lastOk = java.time.Instant.now(); else { lastFailure = java.time.Instant.now(); lastFailureMessage = "invalid response: " + v.errors().get(0); }
            return persist(id, req, v.valid() ? Status.VALID : Status.INVALID, v.assessment(), raw.text(), v.parsedJson(),
                    v.errors(), raw.latencyMs(), raw.inputTokens(), raw.outputTokens(), hash, requestJson, null);
        } catch (AiException e) {
            Status s = switch (e.kind()) { case TIMEOUT -> Status.TIMEOUT; case REFUSED -> Status.REFUSED; default -> Status.ERROR; };
            log.warn("AI analysis failed for {} ({}): {}", req.instrumentLabel(), e.kind(), e.getMessage());
            lastFailure = java.time.Instant.now();
            lastFailureMessage = e.kind() + ": " + e.getMessage();
            return persist(id, req, s, null, null, null, List.of(e.getMessage()), null, null, null, hash, requestJson, null);
        } catch (RuntimeException e) {
            log.warn("AI analysis unexpected failure for {}: {}", req.instrumentLabel(), e.getClass().getSimpleName());
            return persist(id, req, Status.ERROR, null, null, null, List.of("unexpected: " + e.getClass().getSimpleName()), null, null, null, hash, requestJson, null);
        }
    }

    /** true when the validated AI decision points the same way as the rules and does not reject the trade. */
    public static boolean agrees(Decision rules, AiAssessment a) {
        return a != null && a.decision() == rules && !a.rejectTrade();
    }

    public static String disagreementNote(Decision rules, AnalysisOutcome o) {
        if (!o.isValid()) return "no valid AI verdict (" + o.status() + ")";
        AiAssessment a = o.assessment();
        if (agrees(rules, a)) return null;
        String why = a.disagreementExplanation() == null || a.disagreementExplanation().isBlank()
                ? String.join("; ", a.contradicting()) : a.disagreementExplanation();
        return ("rules " + rules + " vs AI " + a.decision() + (a.rejectTrade() ? " (reject_trade)" : "") + ": " + why);
    }

    private AnalysisOutcome persist(String id, AnalysisRequest req, Status status, AiAssessment assessment, String raw,
                                    String parsed, List<String> errors, Long latency, Long inTok, Long outTok, String hash,
                                    String requestJson, String reusedFrom) {
        String errJson = errors == null || errors.isEmpty() ? null : toJson(errors);
        records.insert(new NewRecord(id, req.runId(), req.runMode(), req.signalId(), analyst.name(), analyst.modelId(),
                cfg.promptVersion(), ClaudeAssessmentWire.SCHEMA_VERSION, hash, requestJson, raw, parsed, status.name(),
                errJson, latency, inTok, outTok, reusedFrom));
        return new AnalysisOutcome(status, assessment, analyst.name(), analyst.modelId(), requestJson, raw, parsed,
                errors == null ? List.of() : errors, latency, inTok, outTok, hash, cfg.promptVersion(), reusedFrom, id);
    }

    private String requestJson(String userPrompt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analyst", analyst.name());
        m.put("model", analyst.modelId());
        m.put("prompt_version", cfg.promptVersion());
        m.put("schema_version", ClaudeAssessmentWire.SCHEMA_VERSION);
        m.put("user_prompt", userPrompt);
        return toJson(m);
    }

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); } catch (JsonProcessingException e) { return "\"<unserialisable>\""; }
    }

    private static Long toLong(Object o) { return o instanceof Number n ? n.longValue() : null; }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
