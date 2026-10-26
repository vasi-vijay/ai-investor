package com.aiinvestor.api;

import com.aiinvestor.broker.BrokerAccountProvider;
import com.aiinvestor.broker.BrokerDtos.AccountFunds;
import com.aiinvestor.groww.GrowwAuthManager;
import com.aiinvestor.persistence.AuditRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Broker Connection page API. Connecting never enables live trading (that is a separate, gated flow in LiveController).
 * Credentials are never accepted from or returned to the browser: they come from the server environment only.
 */
@RestController
@RequestMapping("/api/broker")
public class BrokerController {
    private final IntegrationService integrations;
    private final ObjectProvider<GrowwAuthManager> groww;
    private final ObjectProvider<BrokerAccountProvider> account;
    private final AuditRepository audit;

    public BrokerController(IntegrationService integrations, ObjectProvider<GrowwAuthManager> groww,
                            ObjectProvider<BrokerAccountProvider> account, AuditRepository audit) {
        this.integrations = integrations; this.groww = groww; this.account = account; this.audit = audit;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("broker", "Groww");
        m.put("operating_state", integrations.state());
        m.put("trading_paused", integrations.tradingPaused());
        m.put("data", integrations.data());
        m.put("claude", integrations.claude());
        m.put("probe", integrations.cachedProbe());
        m.put("live_trading_enabled_by_connection", false);
        return m;
    }

    /** Runs the read-only capability probe (and reads funds for display). */
    @PostMapping("/verify")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> verify(Authentication auth) {
        audit.log(auth.getName(), "BROKER_VERIFY", "broker", "groww", "read-only capability probe");
        return integrations.probe();
    }

    @GetMapping("/funds")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> funds() {
        BrokerAccountProvider a = account.getIfAvailable();
        if (a == null || integrations.simulatedData()) throw new ResponseStatusException(HttpStatus.CONFLICT, "no live broker account (data source is simulated)");
        AccountFunds f = a.funds();
        return Map.of("available_cash", f.availableCash(), "used_margin", f.usedMargin(), "total_balance", f.totalBalance(), "source", "GROWW_LIVE");
    }

    @GetMapping("/positions")
    @PreAuthorize("hasRole('ADMIN')")
    public Object positions() {
        BrokerAccountProvider a = account.getIfAvailable();
        if (a == null || integrations.simulatedData()) throw new ResponseStatusException(HttpStatus.CONFLICT, "no live broker account (data source is simulated)");
        return a.positions();
    }

    @GetMapping("/orders")
    @PreAuthorize("hasRole('ADMIN')")
    public Object orders() {
        BrokerAccountProvider a = account.getIfAvailable();
        if (a == null || integrations.simulatedData()) throw new ResponseStatusException(HttpStatus.CONFLICT, "no live broker account (data source is simulated)");
        return a.orders();
    }

    /** Connect / reauthorise using the documented token flows with server-side credentials. */
    @PostMapping("/connect")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> connect(Authentication auth) {
        GrowwAuthManager g = groww.getIfAvailable();
        if (g == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Groww adapter is not active (DATA_SOURCE is not GROWW)");
        GrowwAuthManager.State s = g.reauthorizeNow();
        audit.log(auth.getName(), "BROKER_CONNECT", "broker", "groww", "result=" + s);
        return status();
    }

    @PostMapping("/disconnect")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> disconnect(Authentication auth) {
        GrowwAuthManager g = groww.getIfAvailable();
        if (g == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Groww adapter is not active");
        g.disconnect();
        audit.log(auth.getName(), "BROKER_DISCONNECT", "broker", "groww", "operator disconnect; open positions are NOT liquidated");
        return status();
    }

    public record PauseRequest(boolean paused) {}

    @PostMapping("/pause")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> pause(@RequestBody PauseRequest r, Authentication auth) {
        integrations.setPaused(r.paused(), auth.getName());
        audit.log(auth.getName(), r.paused() ? "TRADING_PAUSED" : "TRADING_RESUMED", "settings", IntegrationService.PAUSED, "");
        return status();
    }
}
