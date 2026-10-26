package com.aiinvestor.engine;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Point-in-time view of market data used by the trading engine. By construction it never exposes data after
 * {@link #asOf()}: the same engine code therefore runs look-ahead-free in paper-live, replay and backtests.
 */
public interface MarketView {
    Instant asOf();

    /** Completed {@code timeframeMinutes} candles (end <= asOf) including warm-up days, ascending. */
    List<Candle> candles(Instrument instrument, int timeframeMinutes);

    /** Completed 1-minute bars with start in [from, to) and end <= asOf, ascending (execution simulation input). */
    List<Candle> bars1m(Instrument instrument, Instant from, Instant to);

    /** Quote as of asOf. Present only if the underlying data exists; {@code synthetic()} says how it was built. */
    Optional<Quote> quote(Instrument instrument);

    /** True when quotes are derived from candles (no real order book) — risk checks then use assumed spreads. */
    boolean syntheticQuotes();

    String dataSource();
}
