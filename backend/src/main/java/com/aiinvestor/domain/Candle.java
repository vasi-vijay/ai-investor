package com.aiinvestor.domain;

import java.time.Duration;
import java.time.Instant;

/** OHLCV bar identified by its start instant (UTC). A bar is complete only once start+interval <= now. */
public record Candle(Instant start, double open, double high, double low, double close, long volume, Long openInterest) {

    public Candle(Instant start, double open, double high, double low, double close, long volume) {
        this(start, open, high, low, close, volume, null);
    }

    public Instant end(int intervalMinutes) { return start.plus(Duration.ofMinutes(intervalMinutes)); }

    public boolean isConsistent() {
        return high >= low && high >= open && high >= close && low <= open && low <= close
                && open > 0 && low > 0 && volume >= 0
                && Double.isFinite(open) && Double.isFinite(high) && Double.isFinite(low) && Double.isFinite(close);
    }
}
