package com.aiinvestor.persistence;

import com.aiinvestor.domain.Enums.Decision;
import com.aiinvestor.domain.Enums.FinalStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** trading_signals: one row per strategy evaluation (the decision record), including NO_TRADE and rejected ones. */
@Repository
public class SignalRepository {

    public record NewSignal(String id, String runId, String runMode, String idempotencyKey, Instant barTime, long instrumentId,
                            String underlying, String strategyName, String strategyVersion, String timeframe,
                            Decision ruleDecision, String ruleReasonsJson, BigDecimal entry, BigDecimal stop,
                            BigDecimal target, BigDecimal riskReward, String regime, String indicatorsJson,
                            String snapshotJson, String snapshotHash, FinalStatus finalStatus, String rejectionReason) {}

    public record Inserted(String id, boolean created) {}

    private final JdbcClient jdbc;

    public SignalRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Idempotent: a repeated (run, key) returns the existing id with created=false. */
    public Inserted insert(NewSignal s) {
        String id = s.id() != null ? s.id() : Db.newId();
        try {
            jdbc.sql("""
                    INSERT INTO trading_signals (id, run_id, run_mode, idempotency_key, created_at, bar_time, instrument_id,
                      underlying_symbol, strategy_name, strategy_version, timeframe, rule_decision, rule_reasons_json,
                      proposed_entry, proposed_stop, proposed_target, risk_reward, market_regime, indicators_json,
                      snapshot_json, snapshot_hash, final_status, rejection_reason)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
                    .params(id, s.runId(), s.runMode(), s.idempotencyKey(), Db.ts(Instant.now()), Db.ts(s.barTime()),
                            s.instrumentId(), s.underlying(), s.strategyName(), s.strategyVersion(), s.timeframe(),
                            s.ruleDecision().name(), s.ruleReasonsJson(), s.entry(), s.stop(), s.target(), s.riskReward(),
                            s.regime(), s.indicatorsJson(), s.snapshotJson(), s.snapshotHash(), s.finalStatus().name(),
                            s.rejectionReason())
                    .update();
            return new Inserted(id, true);
        } catch (DuplicateKeyException e) {
            String existing = jdbc.sql("SELECT id FROM trading_signals WHERE run_id=? AND idempotency_key=?")
                    .params(s.runId(), s.idempotencyKey()).query(String.class).single();
            return new Inserted(existing, false);
        }
    }

    public boolean exists(String runId, String idempotencyKey) {
        return jdbc.sql("SELECT COUNT(*) FROM trading_signals WHERE run_id=? AND idempotency_key=?")
                .params(runId, idempotencyKey).query(Integer.class).single() > 0;
    }

    public void recordAi(String signalId, String aiAnalysisId, String aiDecision, Boolean agrees, String disagreement) {
        jdbc.sql("UPDATE trading_signals SET ai_analysis_id=?, ai_decision=?, ai_agrees=?, disagreement_note=? WHERE id=?")
                .params(aiAnalysisId, aiDecision, agrees == null ? null : (agrees ? 1 : 0), trunc(disagreement, 255), signalId).update();
    }

    public void recordTradedInstrument(String signalId, long instrumentId) {
        jdbc.sql("UPDATE trading_signals SET traded_instrument_id=? WHERE id=?").params(instrumentId, signalId).update();
    }

    public void recordRisk(String signalId, String riskDecision, String riskReasonsJson) {
        jdbc.sql("UPDATE trading_signals SET risk_decision=?, risk_reasons_json=? WHERE id=?")
                .params(riskDecision, riskReasonsJson, signalId).update();
    }

    public void finalize(String signalId, FinalStatus status, String rejectionReason, String orderId, String positionId) {
        jdbc.sql("UPDATE trading_signals SET final_status=?, rejection_reason=?, order_id=?, position_id=? WHERE id=?")
                .params(status.name(), trunc(rejectionReason, 500), orderId, positionId, signalId).update();
    }

    public void recordOutcome(String signalId, String outcomeStatus, BigDecimal pnl, String subsequentMoveJson) {
        jdbc.sql("UPDATE trading_signals SET outcome_status=?, outcome_pnl=?, subsequent_move_json=? WHERE id=?")
                .params(outcomeStatus, pnl, subsequentMoveJson, signalId).update();
    }

    public void recordSubsequentMove(String signalId, String json) {
        jdbc.sql("UPDATE trading_signals SET subsequent_move_json=? WHERE id=?").params(json, signalId).update();
    }

    public Optional<Map<String, Object>> get(String id) {
        return jdbc.sql("""
                SELECT s.*, i.trading_symbol AS instrument_symbol FROM trading_signals s
                JOIN instruments i ON i.id = s.instrument_id WHERE s.id=?""").param(id).query().listOfRows().stream().findFirst();
    }

    public List<Map<String, Object>> recent(String runId, int limit, boolean includeNoTrade) {
        String filter = includeNoTrade ? "" : " AND s.rule_decision IN ('BUY_CANDIDATE','SELL_CANDIDATE') ";
        return jdbc.sql("""
                SELECT s.id, s.run_id, s.bar_time, s.created_at, i.trading_symbol AS instrument_symbol, s.underlying_symbol,
                  s.strategy_name, s.timeframe, s.rule_decision, s.rule_reasons_json, s.proposed_entry, s.proposed_stop,
                  s.proposed_target, s.risk_reward, s.market_regime, s.ai_analysis_id, s.ai_decision, s.ai_agrees,
                  s.disagreement_note, s.risk_decision, s.risk_reasons_json, s.final_status, s.rejection_reason,
                  s.order_id, s.position_id, s.outcome_status, s.outcome_pnl, s.traded_instrument_id
                FROM trading_signals s JOIN instruments i ON i.id = s.instrument_id
                WHERE s.run_id=? """ + filter + " ORDER BY s.bar_time DESC, s.created_at DESC LIMIT ?")
                .params(runId, limit).query().listOfRows();
    }

    public List<Map<String, Object>> forRun(String runId) {
        return jdbc.sql("""
                SELECT s.*, i.trading_symbol AS instrument_symbol FROM trading_signals s JOIN instruments i ON i.id = s.instrument_id
                WHERE s.run_id=? ORDER BY s.bar_time, s.created_at""").param(runId).query().listOfRows();
    }

    private static String trunc(String s, int n) { return s == null ? null : s.substring(0, Math.min(s.length(), n)); }
}
