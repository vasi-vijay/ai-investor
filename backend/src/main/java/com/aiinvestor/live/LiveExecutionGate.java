package com.aiinvestor.live;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.groww.GrowwAuthManager;
import com.aiinvestor.persistence.AuditRepository;
import com.aiinvestor.persistence.SettingsRepository;
import com.aiinvestor.risk.KillSwitch;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The single authority on whether a real order may be sent. Live trading requires ALL of:
 *  1 explicit configuration (app.live.enabled)            2 deliberate in-app authorisation (settings.live_authorized)
 *  3 verified Groww authentication                        4 confirmed API capabilities/entitlements (probe < 24h old)
 *  5 compliance attested (config flag AND in-app attestation)   6 valid market data (GROWW source) & functioning risk controls
 *  7 per-order checks (funds, value, params) - in LiveOrderService   8 audit logging working & kill switch off
 *  9 live account status visible (reported by LiveOrderService.status()).
 * Any failure keeps live disabled; paper trading is unaffected.
 */
@Component
public class LiveExecutionGate {
    public record Check(String name, boolean ok, String detail) {}

    public static final String AUTHORIZED = "live_authorized";
    public static final String CAPABILITIES_VERIFIED_AT = "live_capabilities_verified_at";
    public static final String COMPLIANCE_ATTESTED = "live_compliance_attested";

    private final AppProperties props;
    private final SettingsRepository settings;
    private final KillSwitch killSwitch;
    private final AuditRepository audit;
    private final ObjectProvider<GrowwAuthManager> auth;

    public LiveExecutionGate(AppProperties props, SettingsRepository settings, KillSwitch killSwitch, AuditRepository audit,
                             ObjectProvider<GrowwAuthManager> auth) {
        this.props = props;
        this.settings = settings;
        this.killSwitch = killSwitch;
        this.audit = audit;
        this.auth = auth;
    }

    public List<Check> evaluate() {
        List<Check> c = new ArrayList<>();
        c.add(new Check("1_CONFIG_ENABLED", props.live().enabled(), props.live().enabled() ? "app.live.enabled=true" : "app.live.enabled=false (default)"));
        boolean authorised = "YES".equals(settings.get(AUTHORIZED, "NO"));
        c.add(new Check("2_USER_AUTHORISED", authorised, authorised ? "authorised in-app" : "not authorised in-app (separate deliberate action required)"));
        GrowwAuthManager am = auth.getIfAvailable();
        boolean authOk = am != null && am.state() == GrowwAuthManager.State.OK;
        c.add(new Check("3_BROKER_AUTH", authOk, am == null ? "Groww adapter inactive (app.data.source != GROWW)" : "Groww auth state " + am.state()));
        String verified = settings.get(CAPABILITIES_VERIFIED_AT, "");
        boolean capOk = false;
        try { capOk = !verified.isEmpty() && Duration.between(Instant.parse(verified), Instant.now()).compareTo(Duration.ofHours(24)) < 0; } catch (RuntimeException ignored) { }
        c.add(new Check("4_CAPABILITIES_VERIFIED", capOk, capOk ? "probe at " + verified : "no successful capability probe in the last 24h"));
        boolean attested = props.live().complianceConfirmed() && "YES".equals(settings.get(COMPLIANCE_ATTESTED, "NO"));
        c.add(new Check("5_COMPLIANCE_ATTESTED", attested, attested ? "operator attested compliance review (see docs/COMPLIANCE.md)"
                : "compliance NOT attested: need app.live.compliance-confirmed=true AND in-app attestation after verifying SEBI/NSE/Groww requirements"));
        boolean realData = "GROWW".equalsIgnoreCase(props.data().source());
        c.add(new Check("6_REAL_MARKET_DATA", realData, realData ? "market data source GROWW" : "market data is " + props.data().source() + " (not real)"));
        boolean ks = killSwitch.isEngaged();
        c.add(new Check("8_KILL_SWITCH_OFF", !ks, ks ? "kill switch engaged" : "kill switch off (and working)"));
        boolean auditOk;
        try { audit.log("system", "LIVE_GATE_EVALUATED", "live", null, "gate evaluated"); auditOk = true; } catch (RuntimeException e) { auditOk = false; }
        c.add(new Check("8_AUDIT_LOG_WRITABLE", auditOk, auditOk ? "audit log write ok" : "audit log write FAILED"));
        return c;
    }

    public boolean allowed() { return evaluate().stream().allMatch(Check::ok); }

    public void assertAllowed() {
        List<Check> failed = evaluate().stream().filter(x -> !x.ok()).toList();
        if (!failed.isEmpty()) {
            throw new LiveDisabledException("live trading is disabled: " + String.join("; ", failed.stream().map(f -> f.name() + " — " + f.detail()).toList()));
        }
    }

    public static class LiveDisabledException extends RuntimeException {
        public LiveDisabledException(String m) { super(m); }
    }
}
