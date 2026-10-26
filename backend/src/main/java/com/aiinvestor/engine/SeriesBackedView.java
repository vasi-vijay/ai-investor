package com.aiinvestor.engine;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.market.CandleSeries;
import com.aiinvestor.market.HistoricalDataProvider;
import com.aiinvestor.market.MarketSession;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MarketView backed by a HistoricalDataProvider's 1-minute candles, cached per instrument/day. Used by backtests and
 * by replay-mode paper runs. Quotes are synthesised from the last completed 1-minute bar (mid = close, spread assumed).
 * Cached days are only ever sliced at asOf, so no future data can leak.
 */
public class SeriesBackedView implements MarketView {
    private final HistoricalDataProvider history;
    private final MarketSession session;
    private final int warmupDays;
    private final double equitySpreadBps;
    private final double optionSpreadPct;
    private final String source;
    private final Map<String, List<Candle>> dayCache;
    private volatile Instant asOf;

    public SeriesBackedView(HistoricalDataProvider history, MarketSession session, int warmupDays, double equitySpreadBps,
                            double optionSpreadPct, String source, Map<String, List<Candle>> sharedCache) {
        this.history = history;
        this.session = session;
        this.warmupDays = warmupDays;
        this.equitySpreadBps = equitySpreadBps;
        this.optionSpreadPct = optionSpreadPct;
        this.source = source;
        this.dayCache = sharedCache == null ? new ConcurrentHashMap<>() : sharedCache;
    }

    public void setAsOf(Instant t) { this.asOf = t; }

    @Override public Instant asOf() { return asOf; }
    @Override public boolean syntheticQuotes() { return true; }
    @Override public String dataSource() { return source; }

    private List<Candle> day(Instrument i, LocalDate d) {
        return dayCache.computeIfAbsent(i.tradingSymbol() + "|" + d, k ->
                history.getCandles(i, 1, session.openInstant(d), session.closeInstant(d)));
    }

    private List<Candle> minuteRange(Instrument i, LocalDate fromDay, LocalDate toDay, Instant cutoff) {
        List<Candle> out = new ArrayList<>();
        for (LocalDate d = fromDay; !d.isAfter(toDay); d = d.plusDays(1)) {
            if (!session.isTradingDay(d)) continue;
            for (Candle c : day(i, d)) {
                if (!c.end(1).isAfter(cutoff)) out.add(c); else break;
            }
        }
        return out;
    }

    @Override public List<Candle> candles(Instrument i, int tf) {
        LocalDate today = session.dateOf(asOf);
        LocalDate start = today;
        int back = 0;
        while (back < warmupDays) { start = session.previousTradingDay(start); back++; }
        List<Candle> minute = minuteRange(i, start, today, asOf);
        return CandleSeries.aggregate(minute, tf, session, asOf);
    }

    @Override public List<Candle> bars1m(Instrument i, Instant from, Instant to) {
        Instant cutoff = to.isAfter(asOf) ? asOf : to;
        List<Candle> out = new ArrayList<>();
        for (LocalDate d = session.dateOf(from); !d.isAfter(session.dateOf(cutoff)); d = d.plusDays(1)) {
            if (!session.isTradingDay(d)) continue;
            for (Candle c : day(i, d)) {
                if (c.start().isBefore(from)) continue;
                if (c.end(1).isAfter(cutoff)) break;
                out.add(c);
            }
        }
        return out;
    }

    @Override public Optional<Quote> quote(Instrument i) {
        LocalDate today = session.dateOf(asOf);
        List<Candle> d = day(i, today);
        Candle last = null;
        long vol = 0;
        for (Candle c : d) {
            if (c.end(1).isAfter(asOf)) break;
            last = c;
            vol += c.volume();
        }
        if (last == null) return Optional.empty();
        double tick = i.tickSize().doubleValue();
        double halfSpread = i.isOption() ? Math.max(tick, last.close() * optionSpreadPct / 100.0) / 2 : Math.max(tick, last.close() * equitySpreadBps / 10_000.0) / 2;
        double bid = Math.max(tick, Math.floor((last.close() - halfSpread) / tick) * tick);
        double ask = Math.ceil((last.close() + halfSpread) / tick) * tick;
        return Optional.of(new Quote(i.tradingSymbol(), last.close(), bid, ask, 0, 0, vol, last.openInterest(), last.end(1), source + "(derived)"));
    }
}
