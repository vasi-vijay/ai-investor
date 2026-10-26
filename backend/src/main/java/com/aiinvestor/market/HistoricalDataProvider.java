package com.aiinvestor.market;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Instrument;

import java.time.Instant;
import java.util.List;

public interface HistoricalDataProvider {
    /**
     * Candles with start in [from, to), ascending. May include the still-forming candle; callers filter
     * incomplete bars (see {@code CandleSeries.completedUpTo}).
     */
    List<Candle> getCandles(Instrument instrument, int intervalMinutes, Instant from, Instant to);

    String sourceName();
}
