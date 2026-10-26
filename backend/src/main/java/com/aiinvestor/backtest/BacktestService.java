package com.aiinvestor.backtest;

import com.aiinvestor.analytics.AnalyticsService;
import com.aiinvestor.analytics.StatsCalculator;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Enums.AiMode;
import com.aiinvestor.domain.Enums.RunMode;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.engine.*;
import com.aiinvestor.market.HistoricalDataProvider;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.persistence.RunRepository;
import com.aiinvestor.persistence.RunRepository.Run;
import com.aiinvestor.persistence.SignalRepository;
import com.aiinvestor.strategy.Params;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Historical backtests with the SAME engine as paper trading, fed by a look-ahead-free MarketView. For one date range it
 * runs, with identical data, costs, slippage and risk limits:
 *   A RULES_ONLY — deterministic signals → risk engine → simulated fills
 *   B AI_SHADOW  — same trades as A; the analyst also reviews every signal so verdict-vs-outcome can be studied
 *   C AI_GATED   — signals the analyst does not confirm are dropped, then the same risk engine applies
 */
@Service
public class BacktestService {
    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    public record Request(String name, LocalDate from, LocalDate to, String periodLabel,
                          Map<String, Map<String, Object>> strategyParams, BigDecimal capital, List<AiMode> modes,
                          Integer maxAiCalls, Double equitySlippageBps, Double optionSlippagePct, List<String> strategies) {}

    /** Chronological split into development / validation / out-of-sample windows (trading days). */
    public record SuiteRequest(String name, LocalDate from, LocalDate to, int developmentDays, int validationDays,
                               int outOfSampleDays, Request base) {}

    private final TradingEngine engine;
    private final UniverseService universe;
    private final HistoricalDataProvider history;
    private final MarketSession session;
    private final RunRepository runs;
    private final AnalyticsService analytics;
    private final JdbcClient jdbc;
    private final SignalRepository signals;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final com.aiinvestor.strategy.StrategyRegistry registry;
    private final com.aiinvestor.ai.AiAnalysisService ai;
    private final com.aiinvestor.persistence.StrategyConfigRepository strategyConfigs;

    public BacktestService(TradingEngine engine, UniverseService universe, HistoricalDataProvider history, MarketSession session,
                           RunRepository runs, AnalyticsService analytics, JdbcClient jdbc, SignalRepository signals,
                           AppProperties props, ObjectMapper mapper, com.aiinvestor.strategy.StrategyRegistry registry,
                           com.aiinvestor.ai.AiAnalysisService ai, com.aiinvestor.persistence.StrategyConfigRepository strategyConfigs) {
        this.strategyConfigs = strategyConfigs;
        this.engine = engine;
        this.universe = universe;
        this.history = history;
        this.session = session;
        this.runs = runs;
        this.analytics = analytics;
        this.jdbc = jdbc;
        this.signals = signals;
        this.props = props;
        this.mapper = mapper;
        this.registry = registry;
        this.ai = ai;
    }

    public Map<String, Object> runSuite(SuiteRequest s) {
        List<LocalDate> days = tradingDays(s.from(), s.to());
        int need = s.developmentDays() + s.validationDays() + s.outOfSampleDays();
        if (days.size() < need) throw new IllegalArgumentException("range has " + days.size() + " trading days but split needs " + need);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", s.name());
        out.put("warning", "Tune parameters ONLY on DEVELOPMENT; check VALIDATION; run OUT_OF_SAMPLE once, last. Re-tuning after seeing out-of-sample results invalidates it.");
        int i = 0;
        String[] labels = {"DEVELOPMENT", "VALIDATION", "OUT_OF_SAMPLE"};
        int[] sizes = {s.developmentDays(), s.validationDays(), s.outOfSampleDays()};
        List<Map<String, Object>> periods = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            if (sizes[k] == 0) continue;
            LocalDate from = days.get(i), to = days.get(i + sizes[k] - 1);
            i += sizes[k];
            Request b = s.base();
            Request r = new Request(s.name() + " " + labels[k], from, to, labels[k], b.strategyParams(), b.capital(), b.modes(),
                    b.maxAiCalls(), b.equitySlippageBps(), b.optionSlippagePct(), b.strategies());
            periods.add(run(r));
        }
        out.put("periods", periods);
        return out;
    }

    public Map<String, Object> run(Request req) {
        List<LocalDate> days = tradingDays(req.from(), req.to());
        if (days.isEmpty()) throw new IllegalArgumentException("no trading days in range");
        List<AiMode> modes = req.modes() == null || req.modes().isEmpty() ? List.of(AiMode.RULES_ONLY, AiMode.AI_SHADOW, AiMode.AI_GATED) : req.modes();
        BigDecimal capital = req.capital() == null ? props.paper().initialCapital() : req.capital();
        ExecutionAssumptions base = ExecutionAssumptions.from(props.paper());
        ExecutionAssumptions exec = new ExecutionAssumptions(
                req.equitySlippageBps() == null ? base.equitySlippageBps() : req.equitySlippageBps(),
                req.optionSlippagePct() == null ? base.optionSlippagePct() : req.optionSlippagePct(),
                base.maxParticipation(), base.entryTtlMinutes(), base.entryOrderType(), base.assumedEquitySpreadBps(), base.assumedOptionSpreadPct());
        Map<String, Params> overrides = new LinkedHashMap<>();
        if (req.strategyParams() != null) req.strategyParams().forEach((k, v) -> overrides.put(k, new Params(v)));
        if (req.strategies() != null && !req.strategies().isEmpty()) {
            for (var st : registry.all()) {
                if (!req.strategies().contains(st.name())) overrides.merge(st.name(), Params.of("enabled", false), Params::merge);
            }
        }
        List<Instrument> uni = universe.analysisUniverse();
        Map<String, List<com.aiinvestor.domain.Candle>> sharedCache = new ConcurrentHashMap<>();   // identical data across modes
        int aiBudget = req.maxAiCalls() == null ? props.ai().maxCallsPerBacktest() : req.maxAiCalls();

        Map<AiMode, String> runIds = new LinkedHashMap<>();
        for (AiMode mode : modes) {
            RunContext cfgCtx = new RunContext("cfg", RunMode.BACKTEST, mode, capital, props.risk(), exec, props.data().source(), overrides, true);
            String configJson = json(Map.of("strategies_effective", engine.recordStrategyConfigs(cfgCtx, strategyConfigs),
                    "from", days.get(0).toString(), "to", days.get(days.size() - 1).toString(),
                    "strategyOverrides", req.strategyParams() == null ? Map.of() : req.strategyParams(),
                    "execution", exec, "risk", props.risk(), "analyst", ai.analyst().name(), "model", ai.analyst().modelId(),
                    "promptVersion", props.ai().promptVersion(), "maxAiCalls", aiBudget));
            Run run = runs.create(RunMode.BACKTEST, req.name() + " [" + mode + "]", mode, props.data().source(), capital,
                    session.openInstant(days.get(0)), session.closeInstant(days.get(days.size() - 1)), req.periodLabel(), configJson);
            RunContext ctx = new RunContext(run.id(), RunMode.BACKTEST, mode, capital, props.risk(), exec, props.data().source(), overrides, true);
            SeriesBackedView view = new SeriesBackedView(history, session, props.data().warmupDays(), exec.assumedEquitySpreadBps(),
                    exec.assumedOptionSpreadPct(), props.data().source(), sharedCache);
            try {
                for (LocalDate d : days) simulateDay(ctx, view, uni, d, aiBudget);
                runs.finish(run.id(), "COMPLETED", null);
            } catch (RuntimeException e) {
                runs.finish(run.id(), "FAILED", json(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage())));
                throw e;
            } finally {
                engine.forgetRun(run.id());
            }
            runIds.put(mode, run.id());
            log.info("backtest {} {} finished", req.name(), mode);
        }
        Map<String, Object> report = buildReport(req, days, runIds, capital);
        for (String id : runIds.values()) {
            Map<String, Object> single = analytics.runReport(id);
            single.put("comparison", report.get("comparison"));
            runs.finish(id, "COMPLETED", json(single));
        }
        return report;
    }

    private void simulateDay(RunContext ctx, SeriesBackedView view, List<Instrument> uni, LocalDate day, int aiBudget) {
        int tf = props.data().timeframeMinutes();
        Instant open = session.openInstant(day), flat = session.squareOffInstant(day);
        for (Instant t = open.plus(Duration.ofMinutes(tf)); !t.isAfter(flat); t = t.plus(Duration.ofMinutes(tf))) {
            view.setAsOf(t);
            engine.step(ctx, view, uni, aiBudget);
        }
        view.setAsOf(flat);
        engine.finalizeDay(ctx, view, day);
    }

    // ------------------------------------------------------------------ reporting

    private Map<String, Object> buildReport(Request req, List<LocalDate> days, Map<AiMode, String> runIds, BigDecimal capital) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", req.name());
        out.put("period_label", req.periodLabel());
        out.put("from", days.get(0).toString());
        out.put("to", days.get(days.size() - 1).toString());
        out.put("trading_days", days.size());
        out.put("data_source", props.data().source());
        out.put("analyst", ai.analyst().name());
        out.put("analyst_model", ai.analyst().modelId());
        Map<String, Object> modes = new LinkedHashMap<>();
        for (var e : runIds.entrySet()) {
            Map<String, Object> rep = analytics.runReport(e.getValue());
            rep.put("run_id", e.getValue());
            modes.put(e.getKey().name(), rep);
        }
        out.put("runs", modes);
        out.put("comparison", comparison(days, runIds, capital));
        return out;
    }

    private Map<String, Object> comparison(List<LocalDate> days, Map<AiMode, String> runIds, BigDecimal capital) {
        Map<String, Object> c = new LinkedHashMap<>();
        boolean mock = ai.analyst().name().equals("MOCK_HEURISTIC") || "MOCK".equals(props.data().source());
        if (mock) {
            c.put("data_caveat", "These results use " + (ai.analyst().name().equals("MOCK_HEURISTIC") ? "the deterministic MOCK_HEURISTIC stand-in (NOT Claude)" : "Claude")
                    + (("MOCK".equals(props.data().source())) ? " on SYNTHETIC prices" : "") + ". They show that the machinery works; they say nothing about Claude's skill or about real markets.");
        }
        Map<String, Object> headline = new LinkedHashMap<>();
        for (var e : runIds.entrySet()) {
            Map<String, Object> overall = (Map<String, Object>) analytics.runReport(e.getValue()).get("overall");
            headline.put(e.getKey().name(), overall);
        }
        c.put("headline_by_mode", headline);

        // paired daily comparison: AI_GATED vs RULES_ONLY
        String a = runIds.get(AiMode.RULES_ONLY), g = runIds.get(AiMode.AI_GATED);
        if (a != null && g != null) {
            Map<LocalDate, Double> da = dailyNet(a), dg = dailyNet(g);
            List<Double> diffs = new ArrayList<>();
            List<Map<String, Object>> worse = new ArrayList<>();
            int better = 0, worseN = 0, same = 0;
            for (LocalDate d : days) {
                double x = da.getOrDefault(d, 0.0), y = dg.getOrDefault(d, 0.0), diff = y - x;
                diffs.add(diff);
                if (diff > 0.005) better++; else if (diff < -0.005) { worseN++; worse.add(Map.of("date", d.toString(), "rules_only", x, "ai_gated", y, "difference", StatsCalculator.round(diff))); } else same++;
            }
            worse.sort(Comparator.comparingDouble(m -> (double) m.get("difference")));
            Map<String, Object> paired = new LinkedHashMap<>();
            paired.put("days", days.size());
            paired.put("days_ai_gated_better", better);
            paired.put("days_ai_gated_worse", worseN);
            paired.put("days_equal", same);
            double mean = diffs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            paired.put("mean_daily_difference_inr", StatsCalculator.round(mean));
            if (diffs.size() > 1) {
                double ss = diffs.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
                double half = 1.96 * Math.sqrt(ss / (diffs.size() - 1)) / Math.sqrt(diffs.size());
                paired.put("mean_daily_difference_ci95", List.of(StatsCalculator.round(mean - half), StatsCalculator.round(mean + half)));
            }
            paired.put("worst_days_for_ai_gated", worse.stream().limit(5).toList());
            paired.put("note", "difference = AI_GATED minus RULES_ONLY daily net P&L; a CI containing 0 means no detectable difference");
            c.put("paired_daily_ai_gated_vs_rules_only", paired);
        }

        // cohort analysis on the shadow run: same signals, grouped by analyst verdict
        String shadow = runIds.get(AiMode.AI_SHADOW);
        if (shadow != null) c.put("signal_cohorts_ai_shadow", cohorts(shadow));

        int totalTrades = headline.values().stream().mapToInt(v -> ((Number) ((Map<String, Object>) v).getOrDefault("trade_count", 0)).intValue()).min().orElse(0);
        c.put("conclusion", totalTrades < StatsCalculator.MIN_SAMPLE_FOR_CONCLUSIONS
                ? "INCONCLUSIVE: fewest trades in any mode is " + totalTrades + " (< " + StatsCalculator.MIN_SAMPLE_FOR_CONCLUSIONS + "). No claim that the AI adds or removes value can be supported."
                : "Sample is large enough to report intervals, but a single period is still not evidence of a durable edge; confirm on validation and out-of-sample periods and across market regimes.");
        return c;
    }

    private Map<String, Object> cohorts(String shadowRunId) {
        Map<String, List<Double>> byCohort = new LinkedHashMap<>();
        Map<String, Integer> confusion = new LinkedHashMap<>();
        int failures = 0, candidates = 0;
        for (Map<String, Object> s : signals.forRun(shadowRunId)) {
            String rd = String.valueOf(s.get("rule_decision"));
            if (!rd.equals("BUY_CANDIDATE") && !rd.equals("SELL_CANDIDATE")) continue;
            candidates++;
            Object agrees = s.get("ai_agrees");
            String cohort = agrees == null ? "AI_NO_VERDICT" : (com.aiinvestor.persistence.Db.bool(agrees) ? "AI_AGREED" : "AI_DISAGREED");
            if (agrees == null) failures++;
            Double r = hypotheticalR(s.get("subsequent_move_json"));
            if (r == null) continue;
            byCohort.computeIfAbsent(cohort, k -> new ArrayList<>()).add(r);
            String key = cohort + (r > 0 ? "/hypothetical_win" : "/hypothetical_loss_or_flat");
            confusion.merge(key, 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidates", candidates);
        out.put("candidates_without_ai_verdict", failures);
        Map<String, Object> stats = new LinkedHashMap<>();
        byCohort.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", v.size());
            m.put("mean_r", StatsCalculator.round4(v.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
            m.put("total_r", StatsCalculator.round4(v.stream().mapToDouble(Double::doubleValue).sum()));
            long w = v.stream().filter(x -> x > 0).count();
            m.put("win_rate", StatsCalculator.round4((double) w / v.size()));
            double[] ci = StatsCalculator.wilson((int) w, v.size());
            m.put("win_rate_ci95", List.of(StatsCalculator.round4(ci[0]), StatsCalculator.round4(ci[1])));
            m.put("sufficient_sample", v.size() >= StatsCalculator.MIN_SAMPLE_FOR_CONCLUSIONS);
            stats.put(k, m);
        });
        out.put("hypothetical_outcome_by_ai_verdict", stats);
        out.put("confusion", confusion);
        out.put("note", "Hypothetical outcomes follow the strategy's own levels on the analysed instrument (underlying for index signals), cost-free, in R multiples. If AI_AGREED shows no better mean R than AI_DISAGREED, the analyst adds no filtering value in this sample.");
        return out;
    }

    private Double hypotheticalR(Object json) {
        if (json == null) return null;
        try {
            Map<?, ?> m = mapper.readValue(json.toString(), Map.class);
            Object h = m.get("hypothetical");
            if (h instanceof Map<?, ?> hm && hm.get("r_multiple") instanceof Number n && !"NO_DATA".equals(hm.get("result"))) return n.doubleValue();
        } catch (JsonProcessingException ignored) { }
        return null;
    }

    private Map<LocalDate, Double> dailyNet(String runId) {
        Map<LocalDate, Double> m = new HashMap<>();
        for (Map<String, Object> r : jdbc.sql("SELECT trade_date, realized_pnl_net FROM daily_pnl WHERE run_id=?").param(runId).query().listOfRows()) {
            m.put(com.aiinvestor.persistence.Db.toLocalDate(r.get("trade_date")), ((Number) r.get("realized_pnl_net")).doubleValue());
        }
        return m;
    }

    private List<LocalDate> tradingDays(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) if (session.isTradingDay(d)) out.add(d);
        return out;
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (JsonProcessingException e) { return "{}"; }
    }
}
