package com.aiinvestor.strategy;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.domain.TimeFrame;
import com.aiinvestor.market.MarketSession;

import java.time.Instant;
import java.util.List;

/**
 * Everything a strategy may look at. {@code candles} contains COMPLETED bars only (end <= asOf), ascending,
 * spanning warm-up days plus today. {@code todayStart} indexes the first bar of the current session.
 */
public record CandleContext(Instrument instrument, TimeFrame timeframe, Instant asOf, List<Candle> candles,
                            int todayStart, MarketSession session, Quote quote) {

    public List<Candle> today() { return candles.subList(todayStart, candles.size()); }

    public Candle lastBar() { return candles.get(candles.size() - 1); }

    public int size() { return candles.size(); }

    public Instant lastBarEnd() { return lastBar().end(timeframe.minutes()); }
}
