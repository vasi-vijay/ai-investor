package com.aiinvestor.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** Immutable snapshots of the EFFECTIVE strategy parameters (defaults + configuration + run overrides), keyed by content hash. */
@Repository
public class StrategyConfigRepository {
    private final JdbcClient jdbc;

    public StrategyConfigRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** @return the stored version label "<strategyVersion>#<hash8>" */
    public String record(String strategyName, String strategyVersion, String paramsJson) {
        String label = strategyVersion + "#" + sha8(paramsJson);
        try {
            jdbc.sql("INSERT INTO strategy_configurations (strategy_name, version, params_json, enabled, created_at) VALUES (?,?,?,?,?)")
                    .params(strategyName, label, paramsJson, 1, Db.ts(Instant.now())).update();
        } catch (DuplicateKeyException ignored) { /* same parameters already recorded */ }
        return label;
    }

    private static String sha8(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
