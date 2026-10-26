package com.aiinvestor.api;

import com.aiinvestor.live.LiveOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Live-trading control surface. Everything except GET status requires ADMIN, and every action is rejected unless
 * LiveExecutionGate passes. There is deliberately no endpoint through which AI output can reach these methods.
 */
@RestController
@RequestMapping("/api/live")
public class LiveController {
    public record AuthorizeRequest(@NotBlank String confirmation) {}
    public record AttestRequest(@NotBlank @Size(min = 20, max = 500) String note) {}
    public record ProposeDto(@Positive long instrumentId, @Pattern(regexp = "BUY|SELL") String side, @Pattern(regexp = "MARKET|LIMIT") String orderType,
                             @Positive @Max(1_000_000) int quantity, @DecimalMin("0.05") BigDecimal limitPrice, @NotNull @DecimalMin("0.05") BigDecimal stopLoss,
                             @NotNull @DecimalMin("0.05") BigDecimal target, @Size(max = 36) String signalId) {}
    public record DecisionDto(@Size(max = 300) String note) {}

    private final LiveOrderService live;

    public LiveController(LiveOrderService live) { this.live = live; }

    @GetMapping("/status")
    public Map<String, Object> status() { return live.status(); }

    @GetMapping("/orders")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> orders() { return live.list(); }

    @PostMapping("/authorize")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> authorize(@Valid @RequestBody AuthorizeRequest r, Authentication a) {
        live.authorize(a.getName(), r.confirmation());
        return live.status();
    }

    @PostMapping("/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> revoke(Authentication a) { live.revoke(a.getName()); return live.status(); }

    @PostMapping("/compliance-attestation")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> attest(@Valid @RequestBody AttestRequest r, Authentication a) { live.attestCompliance(a.getName(), r.note()); return live.status(); }

    @PostMapping("/probe-capabilities")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> probe(Authentication a) { return live.probeCapabilities(a.getName()); }

    @PostMapping("/orders/propose")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> propose(@Valid @RequestBody ProposeDto d, Authentication a) {
        return live.propose(new LiveOrderService.ProposeRequest(d.instrumentId(), d.side(), d.orderType(), d.quantity(), d.limitPrice(), d.stopLoss(), d.target(), d.signalId()), a.getName());
    }

    @PostMapping("/orders/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> approve(@PathVariable @Pattern(regexp = "^[0-9a-fA-F\\-]{36}$") String id, Authentication a) { return live.approve(id, a.getName()); }

    @PostMapping("/orders/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> reject(@PathVariable @Pattern(regexp = "^[0-9a-fA-F\\-]{36}$") String id, @Valid @RequestBody DecisionDto d, Authentication a) {
        return live.reject(id, a.getName(), d.note());
    }

    @PostMapping("/orders/{id}/reconcile")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> reconcile(@PathVariable @Pattern(regexp = "^[0-9a-fA-F\\-]{36}$") String id, Authentication a) { return live.reconcile(id, a.getName()); }
}
