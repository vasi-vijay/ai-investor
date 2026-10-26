package com.aiinvestor.ai;

import com.aiinvestor.domain.Enums.DataQuality;
import com.aiinvestor.domain.Enums.Decision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Converts untrusted raw model text into a validated {@link AiAssessment}, or rejects it with exact reasons.
 * Rejection reasons: malformed JSON, schema violations, instrument/strategy/timeframe mismatch, confidence out of
 * range, missing/ill-ordered/implausible prices on candidates, empty reasoning.
 */
@Component
public class AiResponseValidator {
    public record Validated(boolean valid, List<String> errors, AiAssessment assessment, String parsedJson) {}

    private final ObjectMapper mapper;

    public AiResponseValidator(ObjectMapper mapper) { this.mapper = mapper; }

    public Validated validate(String rawText, AnalysisRequest req) {
        List<String> errors = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) return fail("empty response");
        ClaudeAssessmentWire w;
        try {
            w = mapper.readValue(stripFences(rawText), ClaudeAssessmentWire.class);
        } catch (JsonProcessingException e) {
            return fail("malformed JSON: " + firstLine(e.getOriginalMessage()));
        }
        if (w == null) return fail("null response object");
        if (w.decision() == null) errors.add("decision missing or not an allowed value");
        if (w.instrument() == null || !w.instrument().equalsIgnoreCase(req.instrumentLabel()))
            errors.add("instrument mismatch: expected " + req.instrumentLabel() + " got " + w.instrument());
        if (w.strategy() == null || !w.strategy().equalsIgnoreCase(req.strategyName()))
            errors.add("strategy mismatch: expected " + req.strategyName() + " got " + w.strategy());
        if (w.timeframe() == null || !w.timeframe().equalsIgnoreCase(req.timeframe()))
            errors.add("timeframe mismatch: expected " + req.timeframe() + " got " + w.timeframe());
        if (w.confidence() < 0 || w.confidence() > 100) errors.add("confidence out of range 0-100: " + w.confidence());
        if (w.reasoning() == null || w.reasoning().isEmpty() || w.reasoning().stream().allMatch(s -> s == null || s.isBlank()))
            errors.add("reasoning is empty");
        if (w.dataQuality() == null) errors.add("data_quality missing");

        Double entry = value(w.entryPrice()), stop = value(w.stopLoss()), target = value(w.targetPrice());
        for (Double d : new Double[]{entry, stop, target}) {
            if (d != null && (!Double.isFinite(d) || d <= 0)) errors.add("non-positive or non-finite price: " + d);
        }
        if (w.decision() == ClaudeAssessmentWire.Decision.BUY_CANDIDATE || w.decision() == ClaudeAssessmentWire.Decision.SELL_CANDIDATE) {
            if (entry == null || stop == null || target == null) {
                errors.add("candidate decision requires entry_price, stop_loss and target_price");
            } else if (errors.stream().noneMatch(s -> s.startsWith("non-positive"))) {
                boolean buy = w.decision() == ClaudeAssessmentWire.Decision.BUY_CANDIDATE;
                boolean ordered = buy ? (stop < entry && entry < target) : (target < entry && entry < stop);
                if (!ordered) errors.add("price levels are not ordered for " + w.decision() + " (stop " + stop + ", entry " + entry + ", target " + target + ")");
                double band = Math.max(Math.max(6 * (Double.isFinite(req.atr()) ? req.atr() : 0), 0.02 * req.lastClose()), 1.5 * proposalReach(req));
                for (double p : new double[]{entry, stop, target}) {
                    if (Math.abs(p - req.lastClose()) > band) {
                        errors.add(String.format(Locale.ROOT, "implausible price %.4f: more than %.4f away from last close %.4f", p, band, req.lastClose()));
                        break;
                    }
                }
            }
        }
        if (!errors.isEmpty()) return new Validated(false, errors, null, null);

        AiAssessment a = new AiAssessment(w.instrument(), Decision.valueOf(w.decision().name()), w.strategy(), w.timeframe(),
                w.confidence(), entry, stop, target, nn(w.supportingConditions()), nn(w.contradictingConditions()),
                nn(w.invalidationConditions()), nn(w.missingInformation()), w.strategyAgreement() == ClaudeAssessmentWire.Agreement.AGREES,
                w.disagreementExplanation() == null ? "" : w.disagreementExplanation(), w.rejectTrade(), nn(w.reasoning()),
                DataQuality.valueOf(w.dataQuality().name()));
        try {
            return new Validated(true, List.of(), a, mapper.writeValueAsString(a));
        } catch (JsonProcessingException e) {
            return fail("could not serialise validated assessment");
        }
    }

    /** Largest distance of the strategy's own proposed levels from the last close (the model may legitimately echo them). */
    @SuppressWarnings("unchecked")
    private static double proposalReach(AnalysisRequest req) {
        Object p = req.snapshot() == null ? null : req.snapshot().get("strategy_proposal");
        if (!(p instanceof java.util.Map<?, ?> m)) return 0;
        double reach = 0;
        for (String k : new String[]{"entry", "stop", "target"}) {
            if (m.get(k) instanceof Number n) reach = Math.max(reach, Math.abs(n.doubleValue() - req.lastClose()));
        }
        return reach;
    }

    private static Validated fail(String msg) { return new Validated(false, List.of(msg), null, null); }

    private static Double value(Optional<Double> o) { return o == null ? null : o.orElse(null); }

    private static <T> List<T> nn(List<T> l) { return l == null ? List.of() : List.copyOf(l); }

    private static String stripFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl > 0 ? t.substring(nl + 1) : t.substring(3);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static String firstLine(String s) { return s == null ? "" : s.lines().findFirst().orElse(""); }
}
