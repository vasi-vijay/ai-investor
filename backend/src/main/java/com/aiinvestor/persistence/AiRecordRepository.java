package com.aiinvestor.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** ai_analysis_records: raw model output kept separate from the validated parse. */
@Repository
public class AiRecordRepository {

    public record NewRecord(String id, String runId, String runMode, String signalId, String analyst, String model,
                            String promptVersion, String schemaVersion, String promptHash, String requestJson,
                            String rawResponse, String parsedJson, String validationStatus, String validationErrors,
                            Long latencyMs, Long inputTokens, Long outputTokens, String reusedFromId) {}

    private final JdbcClient jdbc;

    public AiRecordRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(NewRecord r) {
        jdbc.sql("""
                INSERT INTO ai_analysis_records (id, run_id, run_mode, signal_id, created_at, analyst, model, prompt_version,
                  schema_version, prompt_hash, request_json, raw_response, parsed_json, validation_status, validation_errors,
                  latency_ms, input_tokens, output_tokens, reused_from_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
                .params(r.id(), r.runId(), r.runMode(), r.signalId(), Db.ts(Instant.now()), r.analyst(), r.model(),
                        r.promptVersion(), r.schemaVersion(), r.promptHash(), r.requestJson(), r.rawResponse(), r.parsedJson(),
                        r.validationStatus(), r.validationErrors(), r.latencyMs(), r.inputTokens(), r.outputTokens(),
                        r.reusedFromId())
                .update();
    }

    /** Most recent VALID analysis for an identical prompt by the same analyst/model (reproducible backtest re-runs). */
    public Optional<Map<String, Object>> findReusable(String promptHash, String analyst, String model, String promptVersion) {
        return jdbc.sql("""
                SELECT id, raw_response, parsed_json, model, input_tokens, output_tokens, latency_ms FROM ai_analysis_records
                WHERE prompt_hash=? AND analyst=? AND COALESCE(model,'')=COALESCE(?, '') AND prompt_version=?
                  AND validation_status='VALID' AND reused_from_id IS NULL ORDER BY created_at DESC LIMIT 1""")
                .params(promptHash, analyst, model, promptVersion).query().listOfRows().stream().findFirst();
    }

    /** signal id → (validation_status, parsed_json) for a batch of signals (dashboard list view). */
    public Map<String, Map<String, Object>> summariesForSignals(List<String> signalIds) {
        if (signalIds.isEmpty()) return Map.of();
        Map<String, Map<String, Object>> out = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbc.sql("SELECT signal_id, analyst, model, validation_status, parsed_json FROM ai_analysis_records WHERE signal_id IN (:ids)")
                .param("ids", signalIds).query().listOfRows()) {
            out.put((String) r.get("signal_id"), r);
        }
        return out;
    }

    public Optional<Map<String, Object>> get(String id) {
        return jdbc.sql("SELECT * FROM ai_analysis_records WHERE id=?").param(id).query().listOfRows().stream().findFirst();
    }

    public Optional<Map<String, Object>> forSignal(String signalId) {
        return jdbc.sql("SELECT * FROM ai_analysis_records WHERE signal_id=? ORDER BY created_at DESC LIMIT 1")
                .param(signalId).query().listOfRows().stream().findFirst();
    }

    public int countLiveCallsOn(java.time.LocalDate dayUtcStart, String runMode) {
        Instant from = dayUtcStart.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        return jdbc.sql("""
                SELECT COUNT(*) FROM ai_analysis_records WHERE created_at >= ? AND analyst='CLAUDE' AND reused_from_id IS NULL
                  AND validation_status <> 'SKIPPED' AND run_mode=?""")
                .params(Db.ts(from), runMode).query(Integer.class).single();
    }

    public List<Map<String, Object>> failures(String runId, int limit) {
        return jdbc.sql("""
                SELECT id, signal_id, created_at, validation_status, validation_errors, latency_ms FROM ai_analysis_records
                WHERE run_id=? AND validation_status NOT IN ('VALID','SKIPPED') ORDER BY created_at DESC LIMIT ?""")
                .params(runId, limit).query().listOfRows();
    }
}
