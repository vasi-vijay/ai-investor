package com.aiinvestor.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class RiskEventRepository {
    private final JdbcClient jdbc;

    public RiskEventRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void record(String runId, String runMode, String signalId, String eventType, String check, String severity, String detail) {
        jdbc.sql("""
                INSERT INTO risk_events (run_id, run_mode, created_at, signal_id, event_type, check_name, severity, detail)
                VALUES (?,?,?,?,?,?,?,?)""")
                .params(runId, runMode, Db.ts(Instant.now()), signalId, eventType, check, severity,
                        detail.substring(0, Math.min(detail.length(), 1000)))
                .update();
    }

    public List<Map<String, Object>> recent(String runId, int limit) {
        return jdbc.sql("SELECT * FROM risk_events WHERE run_id=? ORDER BY id DESC LIMIT ?").params(runId, limit).query().listOfRows();
    }
}
