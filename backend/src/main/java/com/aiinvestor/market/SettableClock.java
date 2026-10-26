package com.aiinvestor.market;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** Clock advanced explicitly by the replay driver or by tests. */
public class SettableClock implements TradingClock {
    private final AtomicReference<Instant> now;

    public SettableClock(Instant start) { this.now = new AtomicReference<>(start); }

    @Override public Instant now() { return now.get(); }

    public void set(Instant t) { now.set(t); }
}
