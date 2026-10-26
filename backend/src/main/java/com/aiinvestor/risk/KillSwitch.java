package com.aiinvestor.risk;

import com.aiinvestor.persistence.AuditRepository;
import com.aiinvestor.persistence.SettingsRepository;
import org.springframework.stereotype.Component;

/**
 * Persistent emergency lock. Engaging blocks every new order (paper and live) until a human disengages it;
 * the state survives restarts because it lives in application_settings. Only authenticated ADMIN endpoints call set().
 */
@Component
public class KillSwitch {
    public static final String KEY = "kill_switch";
    private final SettingsRepository settings;
    private final AuditRepository audit;

    public KillSwitch(SettingsRepository settings, AuditRepository audit) {
        this.settings = settings;
        this.audit = audit;
    }

    public boolean isEngaged() { return "ON".equals(settings.get(KEY, "OFF")); }

    public void set(boolean engaged, String actor, String reason) {
        settings.set(KEY, engaged ? "ON" : "OFF", actor);
        audit.log(actor, engaged ? "KILL_SWITCH_ENGAGED" : "KILL_SWITCH_RELEASED", "settings", KEY, reason);
    }
}
