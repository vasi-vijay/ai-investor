package com.aiinvestor.engine;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.market.CandleSeries;
import com.aiinvestor.market.HistoricalDataProvider;
import com.aiinvestor.market.MarketDataProvider;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.market.ProviderException;
import com.aiinvestor.market.TradingClock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MarketView for paper-live operation: real (or mock) providers, incremental 1-minute candle cache, quotes from the
 * MarketDataProvider (true bid/ask when the source supplies them).
 */
public class LiveMarketView implements MarketView {
    private final HistoricalDataProvider history;
    private final MarketDataProvider quotes;
    private final MarketSession session;
    private final TradingClock clock;
    private final int warmupDays;
    private final String source;
    private final Map<String, List<Candle>> cache = new ConcurrentHashMap<>();
    private final Map<String, Optional<Quote>> quoteCache = new ConcurrentHashMap<>();
    private volatile Instant asOf;

    public LiveMarketView(HistoricalDataProvider history, MarketDataProvider quotes, MarketSession session,
                          TradingClock clock, int warmupDays, String source) {
        this.history = history;
        this.quotes = quotes;
        this.session = session;
        this.clock = clock;
        this.warmupDays = warmupDays;
        this.source = source;
        this.asOf = clock.now();
    }

    /** Moves the view forward to the clock's current time and clears per-step quote memoisation. */
    public void refresh() {
        this.asOf = clock.now();
        quoteCache.clear();
    }

    @Override public Instant asOf() { return asOf; }
    @Override public boolean syntheticQuotes() { return false; }
    @Override public String dataSource() { return source; }

    private List<Candle> minutes(Instrument i) {
        return cache.compute(i.tradingSymbol(), (k, existing) -> {
            List<Candle> list = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            LocalDate start = session.dateOf(asOf);
            for (int n = 0; n < warmupDays; n++) start = session.previousTradingDay(start);
            Instant from = list.isEmpty() ? session.openInstant(start) : list.get(list.size() - 1).start();
            List<Candle> fresh = history.getCandles(i, 1, from, asOf.plusSeconds(60));
            for (Candle c : fresh) {
                if (!list.isEmpty() && !c.start().isAfter(list.get(list.size() - 1).start())) {
                    if (c.start().equals(list.get(list.size() - 1).start())) list.set(list.size() - 1, c);
                    continue;
                }
                list.add(c);
            }
            return list;
        });
    }

    @Override public List<Candle> candles(Instrument i, int tf) {
        return CandleSeries.aggregate(CandleSeries.completedUpTo(minutes(i), 1, asOf), tf, session, asOf);
    }

    @Override public List<Candle> bars1m(Instrument i, Instant from, Instant to) {
        Instant cutoff = to.isAfter(asOf) ? asOf : to;
        List<Candle> out = new ArrayList<>();
        for (Candle c : minutes(i)) {
            if (c.start().isBefore(from)) continue;
            if (c.end(1).isAfter(cutoff)) break;
            out.add(c);
        }
        return out;
    }

    @Override public Optional<Quote> quote(Instrument i) {
        return quoteCache.computeIfAbsent(i.tradingSymbol(), k -> {
            try { return Optional.of(quotes.getQuote(i)); } catch (ProviderException e) { return Optional.empty(); }
        });
    }
}
