package com.aiinvestor.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** Persistent key/value application state (kill switch, live authorisation). Only human-driven endpoints write here. */
@Repository
public class SettingsRepository {
    private final JdbcClient jdbc;

    public SettingsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<String> get(String key) {
        return jdbc.sql("SELECT setting_value FROM application_settings WHERE setting_key=?").param(key).query(String.class).optional();
    }

    public String get(String key, String def) { return get(key).orElse(def); }

    public void set(String key, String value, String actor) {
        int n = jdbc.sql("UPDATE application_settings SET setting_value=?, updated_at=?, updated_by=? WHERE setting_key=?")
                .params(value, Db.ts(Instant.now()), actor, key).update();
        if (n == 0) {
            jdbc.sql("INSERT INTO application_settings (setting_key, setting_value, updated_at, updated_by) VALUES (?,?,?,?)")
                    .params(key, value, Db.ts(Instant.now()), actor).update();
        }
    }
}
