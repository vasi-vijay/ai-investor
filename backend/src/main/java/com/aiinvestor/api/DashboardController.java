package com.aiinvestor.api;

import com.aiinvestor.analytics.AnalyticsService;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Enums.RunMode;
import com.aiinvestor.persistence.*;
import com.aiinvestor.persistence.RunRepository.Run;
import com.aiinvestor.runtime.PaperTradingService;
import com.aiinvestor.strategy.Strategy;
import com.aiinvestor.strategy.StrategyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

/** Read-only dashboard data for the current PAPER run (and historical runs). Every payload is labelled simulated. */
@RestController
@RequestMapping("/api")
public class DashboardController {
    private static final Pattern ID = Pattern.compile("^[0-9a-fA-F\\-]{36}$");

    private final OverviewService overview;
    private final PaperTradingService paper;
    private final SignalRepository signals;
    private final AiRecordRepository aiRecords;
    private final TradeRepository trades;
    private final RiskEventRepository riskEvents;
    private final RunRepository runs;
    private final AnalyticsService analytics;
    private final StrategyRegistry strategies;
    private final AuditRepository audit;
    private final DailyStateRepository daily;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public DashboardController(OverviewService overview, PaperTradingService paper, SignalRepository signals, AiRecordRepository aiRecords,
                               TradeRepository trades, RiskEventRepository riskEvents, RunRepository runs, AnalyticsService analytics,
                               StrategyRegistry strategies, AuditRepository audit, DailyStateRepository daily, AppProperties props, ObjectMapper mapper) {
        this.overview = overview; this.paper = paper; this.signals = signals; this.aiRecords = aiRecords; this.trades = trades;
        this.riskEvents = riskEvents; this.runs = runs; this.analytics = analytics; this.strategies = strategies; this.audit = audit;
        this.daily = daily; this.props = props; this.mapper = mapper;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() { return overview.overview(); }

    @GetMapping("/signals")
    public List<Map<String, Object>> signals(@RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "false") boolean includeNoTrade,
                                             @RequestParam(required = false) String runId) {
        String run = resolveRun(runId);
        List<Map<String, Object>> rows = signals.recent(run, clamp(limit), includeNoTrade);
        Map<String, Map<String, Object>> ai = aiRecords.summariesForSignals(rows.stream().map(r -> (String) r.get("id")).toList());
        rows.forEach(r -> {
            parse(r, "rule_reasons_json", "rule_reasons"); parse(r, "risk_reasons_json", "risk_checks");
            Map<String, Object> a = ai.get((String) r.get("id"));
            if (a != null) {
                r.put("ai_analyst", a.get("analyst"));
                r.put("ai_status", a.get("validation_status"));
                Object pj = a.get("parsed_json");
                if (pj instanceof String js) {
                    try { var n = mapper.readTree(js); r.put("ai_confidence", n.path("confidence").asInt()); } catch (Exception ignored) { }
                }
            }
            r.put("simulated", true);
            normaliseBooleans(r);
        });
        return rows;
    }

    @GetMapping("/signals/{id}")
    public Map<String, Object> signal(@PathVariable String id) {
        check(id);
        Map<String, Object> s = signals.get(id).orElseThrow(() -> new IllegalArgumentException("unknown signal"));
        parse(s, "rule_reasons_json", "rule_reasons"); parse(s, "risk_reasons_json", "risk_checks");
        parse(s, "indicators_json", "indicators"); parse(s, "snapshot_json", "snapshot"); parse(s, "subsequent_move_json", "subsequent_move");
        Map<String, Object> out = new LinkedHashMap<>(s);
        aiRecords.forSignal(id).ifPresent(a -> {
            Map<String, Object> ai = new LinkedHashMap<>(a);
            parse(ai, "parsed_json", "validated_assessment");
            parse(ai, "validation_errors", "validation_error_list");
            ai.remove("request_json");     // large; the snapshot above is the reproducible input
            out.put("ai_record", ai);
        });
        out.put("simulated", true);
        normaliseBooleans(out);
        return out;
    }

    @GetMapping("/ai/records/{id}")
    public Map<String, Object> aiRecord(@PathVariable String id) {
        check(id);
        Map<String, Object> a = aiRecords.get(id).orElseThrow(() -> new IllegalArgumentException("unknown record"));
        Map<String, Object> out = new LinkedHashMap<>(a);
        parse(out, "request_json", "request"); parse(out, "parsed_json", "validated_assessment");
        return out;
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders(@RequestParam(defaultValue = "100") int limit) { return trades.recentOrders(paperRun(), clamp(limit)); }

    @GetMapping("/executions")
    public List<Map<String, Object>> executions(@RequestParam(defaultValue = "100") int limit) { return trades.recentExecutions(paperRun(), clamp(limit)); }

    @GetMapping("/positions")
    public List<Map<String, Object>> positions(@RequestParam(defaultValue = "OPEN") String status, @RequestParam(defaultValue = "100") int limit) {
        if (!status.equals("OPEN") && !status.equals("CLOSED")) throw new IllegalArgumentException("status must be OPEN or CLOSED");
        List<Map<String, Object>> rows = trades.positionsView(paperRun(), status, clamp(limit));
        Map<String, Double> marks = paper.marks();
        for (Map<String, Object> r : rows) {
            Double mk = marks.get((String) r.get("id"));
            r.put("mark_price", mk);
            if ("OPEN".equals(status) && mk != null) {
                double e = ((Number) r.get("avg_entry_price")).doubleValue();
                int q = ((Number) r.get("open_quantity")).intValue();
                r.put("unrealized_pnl", Math.round(("LONG".equals(r.get("side")) ? mk - e : e - mk) * q * 100) / 100.0);
            }
            r.put("simulated", true);
        }
        return rows;
    }

    @GetMapping("/analytics/paper")
    public Map<String, Object> paperAnalytics() { return analytics.runReport(paperRun()); }

    @GetMapping("/analytics/runs/{runId}")
    public Map<String, Object> runAnalytics(@PathVariable String runId) { check(runId); return analytics.runReport(runId); }

    @GetMapping("/analytics/daily")
    public List<Map<String, Object>> dailyPnl(@RequestParam(required = false) String runId) {
        List<Map<String, Object>> rows = daily.history(resolveRun(runId));
        rows.forEach(this::normaliseBooleans);
        return rows;
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam(defaultValue = "BACKTEST") String mode) { return runs.list(RunMode.valueOf(mode.toUpperCase())); }

    @GetMapping("/runs/{id}/report")
    public Object runReport(@PathVariable String id) throws Exception {
        check(id);
        String json = runs.reportJson(id).orElse(null);
        return json == null ? Map.of("status", "no stored report") : mapper.readTree(json);
    }

    @GetMapping("/risk/events")
    public List<Map<String, Object>> riskEvents(@RequestParam(defaultValue = "100") int limit) { return riskEvents.recent(paperRun(), clamp(limit)); }

    @GetMapping("/risk/limits")
    public Map<String, Object> limits() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("global", props.risk().global());
        m.put("equity", props.risk().equity());
        m.put("options", props.risk().options());
        m.put("note", "limits are configuration; neither the AI nor any API call can change them at runtime");
        return m;
    }

    @GetMapping("/strategies")
    public List<Map<String, Object>> strategies() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Strategy s : strategies.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name()); m.put("version", s.version()); m.put("default_parameters", s.defaultParams().values());
            Map<String, Object> cfg = props.strategies() == null ? null : props.strategies().get(s.name());
            m.put("configured_overrides", cfg == null ? Map.of() : cfg);
            out.add(m);
        }
        return out;
    }

    @GetMapping("/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "100") int limit) { return audit.recent(clamp(limit)); }

    // ---- helpers

    private String paperRun() { return paper.currentRun().map(Run::id).orElseThrow(() -> new IllegalStateException("paper run not initialised")); }

    private String resolveRun(String runId) {
        if (runId == null) return paperRun();
        check(runId);
        return runId;
    }

    /** TINYINT(1) columns are exposed as JSON booleans regardless of the database driver. */
    private void normaliseBooleans(Map<String, Object> row) {
        for (String k : new String[]{"ai_agrees", "locked", "simulated", "tradable"}) {
            if (row.containsKey(k) && row.get(k) != null) row.put(k, com.aiinvestor.persistence.Db.bool(row.get(k)));
        }
    }

    private static void check(String id) { if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("invalid id"); }
    private static int clamp(int n) { return Math.max(1, Math.min(500, n)); }

    private void parse(Map<String, Object> row, String from, String to) {
        Object v = row.get(from);
        if (v instanceof String s && !s.isBlank()) {
            try { row.put(to, mapper.readTree(s)); row.remove(from); } catch (Exception ignored) { /* leave raw */ }
        }
    }
}
