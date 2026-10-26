package com.aiinvestor.api;

import com.aiinvestor.ai.AiAnalysisService;
import com.aiinvestor.broker.BrokerAccountProvider;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.groww.GrowwAuthManager;
import com.aiinvestor.live.LiveExecutionGate;
import com.aiinvestor.market.HistoricalDataProvider;
import com.aiinvestor.market.InstrumentMetadataProvider;
import com.aiinvestor.market.MarketDataProvider;
import com.aiinvestor.persistence.SettingsRepository;
import com.aiinvestor.risk.KillSwitch;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single source of truth for "what is really connected": market-data readiness, Claude status and the operating state.
 * The operating state is DERIVED from persisted/real facts (auth state, kill switch, pause flag, live gate) rather than
 * stored, so it cannot drift from reality, and there is no edge that leads from "connected" to live trading — live needs
 * the separate in-app authorisation plus every LiveExecutionGate check.
 */
@Service
public class IntegrationService {
    public enum OperatingState {
        DISCONNECTED, AUTHENTICATING, CONNECTED_READ_ONLY, PAPER_TRADING, LIVE_TRADING_PENDING_AUTHORIZATION,
        LIVE_TRADING_ENABLED, AUTHENTICATION_EXPIRED, TRADING_PAUSED, EMERGENCY_LOCKED, CONNECTION_ERROR
    }

    public static final String PAUSED = "trading_paused";

    private final AppProperties props;
    private final ObjectProvider<GrowwAuthManager> groww;
    private final ObjectProvider<BrokerAccountProvider> account;
    private final MarketDataProvider quotes;
    private final HistoricalDataProvider history;
    private final InstrumentMetadataProvider metadata;
    private final AiAnalysisService ai;
    private final KillSwitch kill;
    private final SettingsRepository settings;
    private final LiveExecutionGate gate;
    private final AtomicReference<Map<String, Object>> lastProbe = new AtomicReference<>();

    public IntegrationService(AppProperties props, ObjectProvider<GrowwAuthManager> groww, ObjectProvider<BrokerAccountProvider> account,
                              MarketDataProvider quotes, HistoricalDataProvider history, InstrumentMetadataProvider metadata,
                              AiAnalysisService ai, KillSwitch kill, SettingsRepository settings, LiveExecutionGate gate) {
        this.props = props; this.groww = groww; this.account = account; this.quotes = quotes; this.history = history;
        this.metadata = metadata; this.ai = ai; this.kill = kill; this.settings = settings; this.gate = gate;
    }

    public boolean simulatedData() { return !"GROWW".equalsIgnoreCase(props.data().source()); }

    /** null when the market-data source is usable; otherwise the reason new signals must not be generated. */
    public String marketDataBlockReason() {
        if (simulatedData()) return null;
        GrowwAuthManager g = groww.getIfAvailable();
        if (g == null) return "Groww adapter inactive";
        return g.state() == GrowwAuthManager.State.OK ? null : "Groww is " + g.state() + " — " + g.message();
    }

    public boolean tradingPaused() { return "YES".equals(settings.get(PAUSED, "NO")); }

    public void setPaused(boolean paused, String actor) { settings.set(PAUSED, paused ? "YES" : "NO", actor); }

    /** Why the engine must not run a cycle right now (null = may run). Covers data, pause and kill switch. */
    public String engineBlockReason() {
        String d = marketDataBlockReason();
        if (d != null) return d;
        if (tradingPaused()) return "trading paused by operator";
        return null;
    }

    public OperatingState state() {
        if (kill.isEngaged()) return OperatingState.EMERGENCY_LOCKED;
        if (simulatedData()) return tradingPaused() ? OperatingState.TRADING_PAUSED : OperatingState.PAPER_TRADING;
        GrowwAuthManager g = groww.getIfAvailable();
        if (g == null) return OperatingState.DISCONNECTED;
        if (g.authenticating()) return OperatingState.AUTHENTICATING;
        switch (g.state()) {
            case NOT_CONFIGURED, DISCONNECTED -> { return OperatingState.DISCONNECTED; }
            case AUTH_EXPIRED -> { return OperatingState.AUTHENTICATION_EXPIRED; }
            case AUTH_FAILED -> { return OperatingState.CONNECTION_ERROR; }
            default -> { }
        }
        if (tradingPaused()) return OperatingState.TRADING_PAUSED;
        if (props.live().enabled() && "YES".equals(settings.get(LiveExecutionGate.AUTHORIZED, "NO")))
            return gate.allowed() ? OperatingState.LIVE_TRADING_ENABLED : OperatingState.LIVE_TRADING_PENDING_AUTHORIZATION;
        return props.scheduler().enabled() ? OperatingState.PAPER_TRADING : OperatingState.CONNECTED_READ_ONLY;
    }

    public Map<String, Object> claude() {
        AiAnalysisService.Health h = ai.health();
        Map<String, Object> cl = new LinkedHashMap<>();
        cl.put("analyst", h.analyst());
        cl.put("model", h.model());
        cl.put("configured", h.configured());
        boolean simulated = "MOCK_HEURISTIC".equals(h.analyst());
        cl.put("simulated", simulated);
        cl.put("status", !ai.enabled() ? "DISABLED" : simulated ? "MOCK_STAND_IN" : !h.configured() ? "NOT_CONFIGURED"
                : h.lastFailure() != null && (h.lastSuccess() == null || h.lastFailure().isAfter(h.lastSuccess())) ? "DEGRADED"
                : h.lastSuccess() == null ? "CONFIGURED_NO_CALLS_YET" : "OK");
        cl.put("last_success", h.lastSuccess());
        cl.put("last_failure", h.lastFailure());
        cl.put("last_failure_message", h.lastFailureMessage());
        if (!simulated && !h.configured()) cl.put("detail", "ANTHROPIC_API_KEY is not set — AI-dependent decisions are blocked (recorded as NO_TRADE)");
        return cl;
    }

    public Map<String, Object> data() {
        Map<String, Object> d = new LinkedHashMap<>();
        GrowwAuthManager g = groww.getIfAvailable();
        d.put("source", props.data().source());
        d.put("simulated", simulatedData());
        d.put("mechanism", simulatedData() ? "SYNTHETIC_REPLAY" : "REST_POLLING");
        d.put("poll_seconds", props.scheduler().pollSeconds());
        d.put("max_quote_age_seconds", props.data().maxQuoteAgeSeconds());
        d.put("state", g == null ? (simulatedData() ? "SIMULATED" : "INACTIVE") : g.state().name());
        d.put("detail", g == null ? (simulatedData() ? "synthetic data — not real prices" : "inactive") : g.message());
        d.put("credentials_present", g != null && g.credentialsPresent());
        d.put("last_auth_success", g == null ? null : g.lastSuccess());
        d.put("blocked_reason", marketDataBlockReason());
        return d;
    }

    public Map<String, Object> cachedProbe() { return lastProbe.get(); }

    /** Read-only capability probe: funds, positions, orders, quote, candles, instrument master. Never touches order endpoints. */
    public Map<String, Object> probe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probed_at", Instant.now());
        out.put("source", props.data().source());
        Map<String, Object> caps = new LinkedHashMap<>();
        String block = marketDataBlockReason();
        if (block != null) {
            for (String c : List.of("instrument_master", "quotes", "historical_candles", "funds", "positions", "orders"))
                caps.put(c, cap("UNAVAILABLE", block, null));
            out.put("capabilities", caps);
            lastProbe.set(out);
            return out;
        }
        Instrument probeInst = null;
        try {
            probeInst = props.universe().equities().stream().map(metadata::findEquity).filter(java.util.Optional::isPresent).map(java.util.Optional::get).findFirst().orElse(null);
            caps.put("instrument_master", probeInst != null ? cap("OK", "resolved " + probeInst.tradingSymbol(), null) : cap("UNAVAILABLE", "no configured equity found in instrument master", null));
        } catch (RuntimeException e) { caps.put("instrument_master", cap("ERROR", safe(e), null)); }
        if (probeInst != null) {
            final Instrument pi = probeInst;
            caps.put("quotes", run(() -> { Quote q = quotes.getQuote(pi); return cap("OK", "last " + q.last() + " @ " + q.timestamp(), q.timestamp()); }));
            caps.put("historical_candles", run(() -> {
                List<Candle> c = history.getCandles(pi, props.data().timeframeMinutes(), Instant.now().minus(Duration.ofDays(3)), Instant.now());
                return cap(c.isEmpty() ? "NO_DATA" : "OK", c.size() + " candles in the last 3 days", c.isEmpty() ? null : c.get(c.size() - 1).start());
            }));
        } else {
            caps.put("quotes", cap("UNAVAILABLE", "no probe instrument", null));
            caps.put("historical_candles", cap("UNAVAILABLE", "no probe instrument", null));
        }
        BrokerAccountProvider acct = account.getIfAvailable();
        if (acct == null || simulatedData()) {
            for (String c : List.of("funds", "positions", "orders")) caps.put(c, cap("UNAVAILABLE", "no broker account provider (simulated data source)", null));
        } else {
            caps.put("funds", run(() -> { var f = acct.funds(); return cap("OK", "available cash " + f.availableCash(), null); }));
            caps.put("positions", run(() -> cap("OK", acct.positions().size() + " positions", null)));
            caps.put("orders", run(() -> cap("OK", acct.orders().size() + " orders", null)));
        }
        out.put("capabilities", caps);
        lastProbe.set(out);
        return out;
    }

    private interface Probe { Map<String, Object> run(); }

    private static Map<String, Object> run(Probe p) {
        try { return p.run(); } catch (RuntimeException e) { return cap("ERROR", safe(e), null); }
    }

    private static Map<String, Object> cap(String status, String detail, Instant asOf) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status); m.put("detail", detail); m.put("as_of", asOf);
        return m;
    }

    /** Exception text only (never a stack, URL query or header); the Redactor also filters logs. */
    private static String safe(RuntimeException e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 200 ? m.substring(0, 200) : m));
    }
}
