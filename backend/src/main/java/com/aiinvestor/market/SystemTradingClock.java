package com.aiinvestor.market;

import java.time.Instant;

public class SystemTradingClock implements TradingClock {
    @Override public Instant now() { return Instant.now(); }
}
