package com.aiinvestor.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class AuditRepository {
    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void log(String actor, String action, String entityType, String entityId, String detail) {
        jdbc.sql("INSERT INTO audit_logs (created_at, actor, action, entity_type, entity_id, detail) VALUES (?,?,?,?,?,?)")
                .params(Db.ts(Instant.now()), actor, action, entityType, entityId,
                        detail == null ? null : detail.substring(0, Math.min(detail.length(), 2000)))
                .update();
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.sql("SELECT * FROM audit_logs ORDER BY id DESC LIMIT ?").param(limit).query().listOfRows();
    }
}
