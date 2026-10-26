package com.aiinvestor.risk;

import com.aiinvestor.domain.Enums.Direction;
import com.aiinvestor.domain.Enums.Side;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Request/response types of the risk engine. */
public final class RiskModels {
    private RiskModels() {}

    /**
     * A proposed paper (or future live) order. {@code side} is the opening side: BUY for long, SELL for short.
     * For options only BUY (long) is ever approved. {@code quote} may be null when no market snapshot exists.
     */
    public record RiskRequest(String signalId, Instrument instrument, Direction direction, Side openingSide,
                              BigDecimal entry, BigDecimal stop, BigDecimal target, Quote quote, boolean quoteSynthetic,
                              Instant now, double avgBarVolume, double recentPremiumMoveFraction,
                              int minutesSinceLastBar) {}

    public record CheckResult(String name, boolean passed, String detail) {}

    /**
     * Result of the independent risk engine. When approved, {@code quantity} is the final size in
     * shares / contract units (lots x lot size for options).
     */
    public record RiskDecision(boolean approved, int quantity, BigDecimal riskAmount, BigDecimal notional,
                               List<CheckResult> checks) {
        public List<CheckResult> failures() { return checks.stream().filter(c -> !c.passed()).toList(); }
        public String rejectionSummary() {
            return String.join("; ", failures().stream().map(c -> c.name() + ": " + c.detail()).toList());
        }
    }
}
