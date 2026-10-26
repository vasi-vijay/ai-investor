package com.aiinvestor.backtest;

import com.aiinvestor.analytics.AnalyticsService;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Enums.AiMode;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.engine.SeriesBackedView;
import com.aiinvestor.market.HistoricalDataProvider;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.mock.MockInstrumentMetadataProvider;
import com.aiinvestor.runtime.ReconciliationService;
import com.aiinvestor.support.AbstractDbTest;
import com.aiinvestor.support.AiTestConfig;
import com.aiinvestor.support.ScriptedAnalyst;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import(AiTestConfig.class)
class BacktestEngineTest extends AbstractDbTest {
    @Autowired BacktestService backtests;
    @Autowired ScriptedAnalyst analyst;
    @Autowired ReconciliationService reconciliation;
    @Autowired AnalyticsService analytics;
    @Autowired HistoricalDataProvider history;
    @Autowired com.aiinvestor.market.InstrumentMetadataProvider metadata;
    @Autowired MarketSession session;
    @Autowired AppProperties props;

    static final LocalDate D1 = LocalDate.of(2025, 3, 3), D3 = LocalDate.of(2025, 3, 5);

    @BeforeEach
    void reset() {
        analyst.calls.set(0);
        analyst.script = ScriptedAnalyst::agree;
        analyst.configured = true;
    }

    private BacktestService.Request req(String name, LocalDate from, LocalDate to, List<AiMode> modes) {
        return new BacktestService.Request(name, from, to, "DEVELOPMENT", null, null, modes, 10_000, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> overall(Map<String, Object> report, String mode) {
        return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) report.get("runs")).get(mode)).get("overall");
    }

    @SuppressWarnings("unchecked")
    private static String runId(Map<String, Object> report, String mode) {
        return (String) ((Map<String, Object>) ((Map<String, Object>) report.get("runs")).get(mode)).get("run_id");
    }

    @Test
    void shadowModeTradesExactlyLikeRulesOnlyAndAnAiOutageGatesEverythingOff() {
        analyst.script = r -> { throw ScriptedAnalyst.timeout(); };
        Map<String, Object> rep = backtests.run(req("outage", D1, D3, null));
        int rules = ((Number) overall(rep, "RULES_ONLY").get("trade_count")).intValue();
        assertThat(rules).as("rules-only should trade on 3 synthetic days").isGreaterThan(0);
        assertThat(overall(rep, "AI_SHADOW").get("trade_count")).isEqualTo(overall(rep, "RULES_ONLY").get("trade_count"));
        assertThat(overall(rep, "AI_SHADOW").get("net_pnl")).isEqualTo(overall(rep, "RULES_ONLY").get("net_pnl"));
        assertThat(overall(rep, "AI_GATED").get("trade_count")).isEqualTo(0);
        Integer gatedRejected = jdbc.sql("SELECT COUNT(*) FROM trading_signals WHERE run_id=? AND final_status='REJECTED' AND rejection_reason LIKE 'AI analysis not usable (TIMEOUT%'")
                .param(runId(rep, "AI_GATED")).query(Integer.class).single();
        assertThat(gatedRejected).isGreaterThan(0);
        // every failure is on record
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_analysis_records WHERE run_id=? AND validation_status='TIMEOUT'").param(runId(rep, "AI_GATED")).query(Integer.class).single()).isGreaterThan(0);
        assertThat(((Map<String, Object>) rep.get("comparison")).get("conclusion").toString()).containsAnyOf("INCONCLUSIVE", "Sample is large enough");
    }

    @Test
    void anAgreeingAiCannotAddTradesBeyondWhatRulesAndRiskAllow() {
        Map<String, Object> rep = backtests.run(req("agree", D1, D3, null));
        assertThat(overall(rep, "AI_GATED").get("trade_count")).isEqualTo(overall(rep, "RULES_ONLY").get("trade_count"));
        assertThat(overall(rep, "AI_GATED").get("net_pnl")).isEqualTo(overall(rep, "RULES_ONLY").get("net_pnl"));
        Integer risky = jdbc.sql("SELECT COUNT(*) FROM trading_signals WHERE run_id=? AND final_status='REJECTED' AND risk_decision='REJECTED'")
                .param(runId(rep, "AI_GATED")).query(Integer.class).single();
        assertThat(risky).as("risk engine still rejects candidates even when the AI agrees").isGreaterThan(0);
    }

    @Test
    void aDisagreeingAiBlocksEveryTradeAndTheDisagreementsAreRecorded() {
        analyst.script = ScriptedAnalyst::disagree;
        Map<String, Object> rep = backtests.run(req("disagree", D1, D3, null));
        assertThat(overall(rep, "AI_GATED").get("trade_count")).isEqualTo(0);
        String gated = runId(rep, "AI_GATED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trading_signals WHERE run_id=? AND rule_decision IN ('BUY_CANDIDATE','SELL_CANDIDATE') AND ai_agrees=1").param(gated).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trading_signals WHERE run_id=? AND ai_agrees=0 AND disagreement_note IS NOT NULL").param(gated).query(Integer.class).single()).isGreaterThan(0);
        @SuppressWarnings("unchecked") Map<String, Object> cohorts = (Map<String, Object>) ((Map<String, Object>) rep.get("comparison")).get("signal_cohorts_ai_shadow");
        assertThat(cohorts).containsKey("hypothetical_outcome_by_ai_verdict");
    }

    @Test
    void backtestsAreReproducible() {
        Map<String, Object> a = backtests.run(req("rep-a", D1, D3, List.of(AiMode.RULES_ONLY)));
        Map<String, Object> b = backtests.run(req("rep-b", D1, D3, List.of(AiMode.RULES_ONLY)));
        assertThat(overall(a, "RULES_ONLY")).isEqualTo(overall(b, "RULES_ONLY"));
        String q = "SELECT strategy_name, bar_time, instrument_id, rule_decision, final_status, proposed_entry FROM trading_signals WHERE run_id=? ORDER BY bar_time, strategy_name, instrument_id";
        assertThat(jdbc.sql(q).param(runId(a, "RULES_ONLY")).query().listOfRows()).isEqualTo(jdbc.sql(q).param(runId(b, "RULES_ONLY")).query().listOfRows());
    }

    @Test
    void decisionsOnADayDoNotDependOnLaterData() {
        Map<String, Object> one = backtests.run(req("one-day", D1, D1, List.of(AiMode.RULES_ONLY)));
        Map<String, Object> three = backtests.run(req("three-day", D1, D3, List.of(AiMode.RULES_ONLY)));
        String q = "SELECT strategy_name, bar_time, instrument_id, rule_decision, final_status, proposed_entry, proposed_stop, proposed_target, market_regime FROM trading_signals WHERE run_id=? AND bar_time < ? ORDER BY bar_time, strategy_name, instrument_id";
        java.sql.Timestamp day2 = java.sql.Timestamp.from(session.openInstant(D1.plusDays(1)));
        List<Map<String, Object>> a = jdbc.sql(q).params(runId(one, "RULES_ONLY"), day2).query().listOfRows();
        List<Map<String, Object>> b = jdbc.sql(q).params(runId(three, "RULES_ONLY"), day2).query().listOfRows();
        assertThat(a).isNotEmpty();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void theMarketViewNeverExposesBarsThatHaveNotCompleted() {
        SeriesBackedView view = new SeriesBackedView(history, session, 3, 3, 1.5, "MOCK", null);
        Instrument reliance = metadata.findEquity("RELIANCE").orElseThrow();
        Instant asOf = session.openInstant(D3).plusSeconds(37 * 60);                      // mid-bar on purpose
        view.setAsOf(asOf);
        List<Candle> five = view.candles(reliance, 5);
        assertThat(five).isNotEmpty();
        assertThat(five).allSatisfy(c -> assertThat(c.end(5)).isBeforeOrEqualTo(asOf));
        List<Candle> one = view.bars1m(reliance, session.openInstant(D3), asOf.plusSeconds(3600));
        assertThat(one).allSatisfy(c -> assertThat(c.end(1)).isBeforeOrEqualTo(asOf));
        assertThat(view.quote(reliance).orElseThrow().timestamp()).isBeforeOrEqualTo(asOf);
    }

    @Test
    void modesAreIsolatedAndBooksReconcile() {
        Map<String, Object> rep = backtests.run(req("iso", D1, D3, null));
        for (String mode : List.of("RULES_ONLY", "AI_SHADOW", "AI_GATED")) {
            String id = runId(rep, mode);
            assertThat(reconciliation.endOfDay(id, "BACKTEST", D3)).as(mode).isEmpty();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_orders WHERE run_id=? AND (run_mode<>'BACKTEST' OR simulated<>1)").param(id).query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM positions WHERE run_id=? AND status='OPEN'").param(id).query(Integer.class).single()).as("flat at end of day").isZero();
            assertThat(analytics.runReport(id).get("run_mode")).isEqualTo("BACKTEST");
        }
        assertThat(jdbc.sql("SELECT COUNT(DISTINCT run_id) FROM positions").query(Integer.class).single()).isLessThanOrEqualTo(3);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trading_runs WHERE run_mode<>'BACKTEST'").query(Integer.class).single()).isZero();
        assertThat(overall(rep, "RULES_ONLY")).containsKeys("net_pnl", "max_drawdown", "profit_factor", "win_rate", "avg_win", "avg_loss", "trade_count");
    }

    @Test
    void chronologicalSplitProducesThreeLabelledPeriodsWithoutOverlap() {
        BacktestService.SuiteRequest s = new BacktestService.SuiteRequest("split", LocalDate.of(2025, 3, 3), LocalDate.of(2025, 3, 14), 4, 3, 3,
                req("split", D1, D3, List.of(AiMode.RULES_ONLY)));
        Map<String, Object> out = backtests.runSuite(s);
        @SuppressWarnings("unchecked") List<Map<String, Object>> periods = (List<Map<String, Object>>) out.get("periods");
        assertThat(periods).extracting(p -> p.get("period_label")).containsExactly("DEVELOPMENT", "VALIDATION", "OUT_OF_SAMPLE");
        for (int i = 1; i < periods.size(); i++) {
            assertThat(LocalDate.parse((String) periods.get(i).get("from"))).isAfter(LocalDate.parse((String) periods.get(i - 1).get("to")));
        }
        assertThat(out.get("warning").toString()).contains("OUT_OF_SAMPLE");
    }
}
