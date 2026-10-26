package com.aiinvestor.domain;

import java.time.Instant;

/** Top-of-book snapshot. bid/ask/oi may be NaN/null when the source does not provide them. */
public record Quote(String symbol, double last, double bid, double ask, long bidQty, long askQty,
                    long volume, Long openInterest, Instant timestamp, String source) {

    public boolean hasBook() { return bid > 0 && ask > 0 && ask >= bid; }
    public double mid() { return hasBook() ? (bid + ask) / 2.0 : last; }
    /** Spread as a fraction of mid; NaN when no two-sided book. */
    public double spreadFraction() { return hasBook() ? (ask - bid) / mid() : Double.NaN; }
}
