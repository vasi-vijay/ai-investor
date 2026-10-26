package com.aiinvestor.persistence;

import com.aiinvestor.domain.Enums.AiMode;
import com.aiinvestor.domain.Enums.RunMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A "run" scopes every order, position, signal and P&L figure; BACKTEST, PAPER and LIVE runs never share rows. */
@Repository
public class RunRepository {
    public record Run(String id, RunMode mode, String name, AiMode aiMode, String dataSource, BigDecimal initialCapital,
                      String status, Instant startedAt) {}

    private final JdbcClient jdbc;

    public RunRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Run create(RunMode mode, String name, AiMode aiMode, String dataSource, BigDecimal capital,
                      Instant periodStart, Instant periodEnd, String periodLabel, String configJson) {
        String id = Db.newId();
        jdbc.sql("""
                INSERT INTO trading_runs (id, run_mode, name, status, ai_mode, data_source, initial_capital, period_start,
                  period_end, period_label, config_json, started_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""")
                .params(id, mode.name(), name, "RUNNING", aiMode.name(), dataSource, capital, Db.ts(periodStart),
                        Db.ts(periodEnd), periodLabel, configJson, Db.ts(Instant.now()))
                .update();
        return new Run(id, mode, name, aiMode, dataSource, capital, "RUNNING", Instant.now());
    }

    /** The long-lived paper run (one per name); created on first start, reused after restart. */
    /** A run is reused only with the SAME data source, so simulated-data and real-data results never share a run. */
    public Run getOrCreatePaperRun(String name, AiMode aiMode, String dataSource, BigDecimal capital) {
        Optional<Run> r = jdbc.sql("SELECT * FROM trading_runs WHERE run_mode='PAPER' AND name=? AND data_source=? AND status='RUNNING'")
                .params(name, dataSource).query((rs, n) -> map(rs)).optional();
        return r.orElseGet(() -> create(RunMode.PAPER, name, aiMode, dataSource, capital, null, null, null, null));
    }

    public Optional<Run> find(String id) {
        return jdbc.sql("SELECT * FROM trading_runs WHERE id=?").param(id).query((rs, n) -> map(rs)).optional();
    }

    public void finish(String id, String status, String reportJson) {
        jdbc.sql("UPDATE trading_runs SET status=?, report_json=?, ended_at=? WHERE id=?")
                .params(status, reportJson, Db.ts(Instant.now()), id).update();
    }

    public void setConfig(String id, String configJson) {
        jdbc.sql("UPDATE trading_runs SET config_json=? WHERE id=?").params(configJson, id).update();
    }

    public Optional<String> reportJson(String id) {
        return jdbc.sql("SELECT report_json FROM trading_runs WHERE id=?").param(id).query(String.class).optional();
    }

    public List<Map<String, Object>> list(RunMode mode) {
        return jdbc.sql("""
                SELECT id, run_mode, name, status, ai_mode, data_source, initial_capital, period_label, period_start,
                       period_end, started_at, ended_at FROM trading_runs WHERE run_mode=? ORDER BY started_at DESC""")
                .param(mode.name()).query().listOfRows();
    }

    private Run map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Run(rs.getString("id"), RunMode.valueOf(rs.getString("run_mode")), rs.getString("name"),
                AiMode.valueOf(rs.getString("ai_mode")), rs.getString("data_source"), rs.getBigDecimal("initial_capital"),
                rs.getString("status"), Db.instant(rs, "started_at"));
    }
}
