package com.aiinvestor.market;

import java.time.Instant;

/** Source of "now". Real in production; settable in replay/backtest/test so behaviour is reproducible. */
public interface TradingClock {
    Instant now();
}
