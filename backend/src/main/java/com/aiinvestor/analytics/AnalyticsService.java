package com.aiinvestor.analytics;

import com.aiinvestor.persistence.RunRepository;
import com.aiinvestor.persistence.SignalRepository;
import com.aiinvestor.persistence.TradeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Per-run analytics. Always scoped to ONE run (and therefore one mode): PAPER, BACKTEST and LIVE statistics are never
 * combined. Every figure is simulated unless the run mode is LIVE.
 */
@Service
public class AnalyticsService {
    private final TradeRepository trades;
    private final SignalRepository signals;
    private final RunRepository runs;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AnalyticsService(TradeRepository trades, SignalRepository signals, RunRepository runs, JdbcClient jdbc, ObjectMapper mapper) {
        this.trades = trades;
        this.signals = signals;
        this.runs = runs;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Map<String, Object> runReport(String runId) {
        RunRepository.Run run = runs.find(runId).orElseThrow(() -> new IllegalArgumentException("unknown run " + runId));
        List<Map<String, Object>> closed = trades.closedTrades(runId);
        double capital = run.initialCapital().doubleValue();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("run_id", runId);
        r.put("run_mode", run.mode().name());
        r.put("ai_mode", run.aiMode().name());
        r.put("data_source", run.dataSource());
        r.put("simulated", run.mode() != com.aiinvestor.domain.Enums.RunMode.LIVE);
        r.put("label", run.mode() == com.aiinvestor.domain.Enums.RunMode.LIVE ? "LIVE broker results" : "SIMULATED results — not real fills, not a profit projection");
        r.put("initial_capital", capital);
        r.put("overall", StatsCalculator.compute(pnls(closed), capital));
        r.put("by_strategy", group(closed, m -> str(m.get("strategy_name")), capital));
        r.put("by_instrument", group(closed, m -> str(m.get("underlying_symbol") != null ? m.get("underlying_symbol") : m.get("instrument_symbol")), capital));
        r.put("by_instrument_type", group(closed, m -> str(m.get("instrument_type")), capital));
        r.put("by_market_regime", group(closed, m -> m.get("market_regime") == null ? "UNKNOWN" : str(m.get("market_regime")), capital));
        r.put("costs_and_slippage", costs(runId, closed));
        r.put("signals", signalStats(runId));
        r.put("ai", aiStats(runId));
        return r;
    }

    private Map<String, Object> costs(String runId, List<Map<String, Object>> closed) {
        Map<String, Object> m = new LinkedHashMap<>();
        double costs = closed.stream().mapToDouble(c -> d(c.get("total_costs"))).sum();
        double gross = closed.stream().mapToDouble(c -> d(c.get("realized_pnl_gross"))).sum();
        Double slip = jdbc.sql("SELECT COALESCE(SUM(slippage_amount),0) FROM trade_executions WHERE run_id=?").param(runId).query(Double.class).single();
        m.put("gross_pnl", StatsCalculator.round(gross));
        m.put("transaction_costs", StatsCalculator.round(costs));
        m.put("net_pnl", StatsCalculator.round(gross - costs));
        m.put("slippage_incurred_included_in_fill_prices", StatsCalculator.round(slip));
        m.put("note", "gross P&L already reflects slippage in fill prices; costs are brokerage + statutory charges (configurable approximations)");
        return m;
    }

    public Map<String, Object> signalStats(String runId) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> row = jdbc.sql("""
                SELECT COUNT(*) total,
                  SUM(CASE WHEN rule_decision IN ('BUY_CANDIDATE','SELL_CANDIDATE') THEN 1 ELSE 0 END) candidates,
                  SUM(CASE WHEN rule_decision = 'NO_TRADE' THEN 1 ELSE 0 END) no_trade,
                  SUM(CASE WHEN rule_decision = 'INSUFFICIENT_DATA' THEN 1 ELSE 0 END) insufficient,
                  SUM(CASE WHEN rule_decision IN ('BUY_CANDIDATE','SELL_CANDIDATE') AND final_status='APPROVED' THEN 1 ELSE 0 END) approved,
                  SUM(CASE WHEN rule_decision IN ('BUY_CANDIDATE','SELL_CANDIDATE') AND final_status='REJECTED' THEN 1 ELSE 0 END) rejected
                FROM trading_signals WHERE run_id=?""").param(runId).query().singleRow();
        long total = l(row.get("total")), cands = l(row.get("candidates"));
        m.put("evaluations_total", total);
        m.put("candidates", cands);
        m.put("approved", l(row.get("approved")));
        m.put("rejected", l(row.get("rejected")));
        m.put("no_trade", l(row.get("no_trade")));
        m.put("insufficient_data", l(row.get("insufficient")));
        m.put("no_trade_frequency", total == 0 ? null : StatsCalculator.round4((double) l(row.get("no_trade")) / total));
        m.put("rejection_reasons_top", jdbc.sql("""
                SELECT SUBSTRING(rejection_reason,1,90) reason, COUNT(*) n FROM trading_signals
                WHERE run_id=? AND final_status='REJECTED' AND rejection_reason IS NOT NULL GROUP BY SUBSTRING(rejection_reason,1,90)
                ORDER BY n DESC LIMIT 8""").param(runId).query().listOfRows());
        return m;
    }

    public Map<String, Object> aiStats(String runId) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> st = jdbc.sql("""
                SELECT validation_status status, COUNT(*) n, COALESCE(SUM(input_tokens),0) in_tok, COALESCE(SUM(output_tokens),0) out_tok,
                       AVG(latency_ms) avg_latency FROM ai_analysis_records WHERE run_id=? GROUP BY validation_status""").param(runId).query().listOfRows();
        m.put("by_status", st);
        Map<String, Object> agr = jdbc.sql("""
                SELECT SUM(CASE WHEN ai_agrees=1 THEN 1 ELSE 0 END) agree, SUM(CASE WHEN ai_agrees=0 THEN 1 ELSE 0 END) disagree,
                       SUM(CASE WHEN ai_analysis_id IS NOT NULL AND ai_agrees IS NULL THEN 1 ELSE 0 END) no_verdict
                FROM trading_signals WHERE run_id=?""").param(runId).query().singleRow();
        m.put("agreement", agr);
        m.put("analysts", jdbc.sql("SELECT analyst, model, COUNT(*) n FROM ai_analysis_records WHERE run_id=? GROUP BY analyst, model").param(runId).query().listOfRows());
        m.put("confidence_note", "confidence is an uncalibrated model output and is not a probability of profit");
        return m;
    }

    private List<Double> pnls(List<Map<String, Object>> closed) {
        List<Double> out = new ArrayList<>();
        for (Map<String, Object> c : closed) out.add(d(c.get("realized_pnl_net")));
        return out;
    }

    private Map<String, Object> group(List<Map<String, Object>> closed, Function<Map<String, Object>, String> key, double capital) {
        Map<String, List<Double>> g = new LinkedHashMap<>();
        for (Map<String, Object> c : closed) g.computeIfAbsent(key.apply(c), k -> new ArrayList<>()).add(d(c.get("realized_pnl_net")));
        Map<String, Object> out = new LinkedHashMap<>();
        g.forEach((k, v) -> out.put(k, StatsCalculator.compute(v, capital)));
        return out;
    }

    private static double d(Object o) { return o instanceof BigDecimal b ? b.doubleValue() : o instanceof Number n ? n.doubleValue() : 0; }
    private static long l(Object o) { return o instanceof Number n ? n.longValue() : 0; }
    private static String str(Object o) { return o == null ? "UNKNOWN" : o.toString(); }
}
