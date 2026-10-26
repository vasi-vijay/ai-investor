package com.aiinvestor.db;

import com.aiinvestor.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationTest extends AbstractDbTest {

    @Test
    void allRequiredTablesExistAndMigrationsSucceeded() {
        List<String> tables = jdbc.sql("SELECT LOWER(table_name) FROM information_schema.tables WHERE table_schema = SCHEMA()").query(String.class).list();
        assertThat(tables).contains("instruments", "market_candles", "strategy_configurations", "trading_signals", "ai_analysis_records", "trade_orders",
                "trade_executions", "positions", "daily_pnl", "risk_events", "application_settings", "audit_logs", "trading_runs", "live_order_approvals");
        List<Boolean> ok = jdbc.sql("SELECT \"success\" FROM \"flyway_schema_history\" WHERE \"version\" IS NOT NULL").query(Boolean.class).list();
        assertThat(ok).hasSize(2).containsOnly(true);
    }

    @Test
    void safeDefaultsAreSeeded() {
        assertThat(jdbc.sql("SELECT setting_value FROM application_settings WHERE setting_key='execution_mode'").query(String.class).single()).isEqualTo("PAPER");
        assertThat(jdbc.sql("SELECT setting_value FROM application_settings WHERE setting_key='kill_switch'").query(String.class).single()).isEqualTo("OFF");
        assertThat(jdbc.sql("SELECT setting_value FROM application_settings WHERE setting_key='live_authorized'").query(String.class).single()).isEqualTo("NO");
    }

    private String run(String mode) {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO trading_runs (id, run_mode, name, status, ai_mode, data_source, initial_capital, started_at) VALUES (?,?,?,?,?,?,?,?)")
                .params(id, mode, "t", "RUNNING", "RULES_ONLY", "MOCK", 1000, Timestamp.from(Instant.now())).update();
        return id;
    }

    private long instrument() {
        jdbc.sql("DELETE FROM instruments WHERE trading_symbol='MIG'").update();
        jdbc.sql("INSERT INTO instruments (exchange, segment, instrument_type, trading_symbol, lot_size, tick_size, source, refreshed_at) VALUES ('NSE','CASH','EQUITY','MIG',1,0.05,'T',?)")
                .param(Timestamp.from(Instant.now())).update();
        return jdbc.sql("SELECT id FROM instruments WHERE trading_symbol='MIG'").query(Long.class).single();
    }

    private void insertOrder(String runId, String runMode, long instrId, String clientId, int simulated) {
        jdbc.sql("INSERT INTO trade_orders (id, run_id, run_mode, client_order_id, instrument_id, side, order_type, purpose, quantity, status, simulated, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .params(java.util.UUID.randomUUID().toString(), runId, runMode, clientId, instrId, "BUY", "MARKET", "ENTRY", 10, "PENDING", simulated,
                        Timestamp.from(Instant.now()), Timestamp.from(Instant.now())).update();
    }

    @Test
    void aPaperOrCandidateRowCanNeverBeMarkedAsARealExecution() {
        String paper = run("PAPER");
        long i = instrument();
        assertThatThrownBy(() -> insertOrder(paper, "PAPER", i, "c1", 0)).isInstanceOf(DataIntegrityViolationException.class);
        String bt = run("BACKTEST");
        assertThatThrownBy(() -> insertOrder(bt, "BACKTEST", i, "c2", 0)).isInstanceOf(DataIntegrityViolationException.class);
        String live = run("LIVE");
        insertOrder(live, "LIVE", i, "c3", 0);          // only LIVE rows may be non-simulated
    }

    @Test
    void runModesAreRestricted() {
        assertThatThrownBy(() -> run("SANDBOX")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateClientOrderIdsAreRejectedWithinARunButAllowedAcrossRuns() {
        String a = run("PAPER"), b = run("BACKTEST");
        long i = instrument();
        insertOrder(a, "PAPER", i, "dup", 1);
        assertThatThrownBy(() -> insertOrder(a, "PAPER", i, "dup", 1)).isInstanceOf(DataIntegrityViolationException.class);
        insertOrder(b, "BACKTEST", i, "dup", 1);
    }

    @Test
    void signalsAreIdempotentPerRunAndKey() {
        String r = run("PAPER");
        long i = instrument();
        String sql = "INSERT INTO trading_signals (id, run_id, run_mode, idempotency_key, created_at, bar_time, instrument_id, strategy_name, strategy_version, timeframe, rule_decision, final_status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        jdbc.sql(sql).params("s1", r, "PAPER", "k", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), i, "S", "1", "5m", "NO_TRADE", "NO_TRADE").update();
        assertThatThrownBy(() -> jdbc.sql(sql).params("s2", r, "PAPER", "k", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), i, "S", "1", "5m", "NO_TRADE", "NO_TRADE").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(sql).params("s3", r, "PAPER", "k2", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), i, "S", "1", "5m", "MAYBE", "NO_TRADE").update())
                .as("rule_decision is constrained to the allowed decisions").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void marketDataAndInstrumentConstraintsHold() {
        long i = instrument();
        String sql = "INSERT INTO market_candles (instrument_id, interval_code, bucket_start, open_price, high_price, low_price, close_price, volume, source) VALUES (?,?,?,?,?,?,?,?,?)";
        assertThatThrownBy(() -> jdbc.sql(sql).params(i, "5m", Timestamp.from(Instant.now()), 10, 9, 8, 10, 1, "T").update())
                .as("high < open violates the OHLC check").isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql(sql).params(i, "5m", Timestamp.from(Instant.parse("2025-03-04T04:00:00Z")), 10, 11, 9, 10, 5, "T").update();
        assertThatThrownBy(() -> jdbc.sql(sql).params(i, "5m", Timestamp.from(Instant.parse("2025-03-04T04:00:00Z")), 10, 11, 9, 10, 5, "T").update())
                .as("candles are unique per instrument/interval/bucket/source").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO instruments (exchange, segment, instrument_type, trading_symbol, lot_size, tick_size, source, refreshed_at) VALUES ('NSE','FNO','OPTION','BADLOT',0,0.05,'T',?)")
                .param(Timestamp.from(Instant.now())).update()).as("lot size must be positive").isInstanceOf(DataIntegrityViolationException.class);
    }
}
