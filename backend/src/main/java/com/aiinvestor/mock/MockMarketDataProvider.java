package com.aiinvestor.mock;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.market.CandleSeries;
import com.aiinvestor.market.HistoricalDataProvider;
import com.aiinvestor.market.MarketDataProvider;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.market.ProviderException;
import com.aiinvestor.market.TradingClock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Synthetic quotes and candles driven by the trading clock. Test hooks allow stale-data/outage injection. */
public class MockMarketDataProvider implements MarketDataProvider, HistoricalDataProvider {
    public static final String SOURCE = "MOCK";
    private final SyntheticMarket market;
    private final MarketSession session;
    private final TradingClock clock;
    private volatile boolean connected = true;
    private volatile Duration quoteAgeOffset = Duration.ZERO;

    public MockMarketDataProvider(SyntheticMarket market, MarketSession session, TradingClock clock) {
        this.market = market;
        this.session = session;
        this.clock = clock;
    }

    public void setConnected(boolean connected) { this.connected = connected; }
    /** Makes every quote timestamp appear this much older (stale-data testing). */
    public void setQuoteAgeOffset(Duration d) { this.quoteAgeOffset = d; }

    private List<Candle> minuteSeries(Instrument i, LocalDate d) {
        return i.isOption() ? market.optionMinuteCandles(i, d) : market.minuteCandles(i.tradingSymbol(), d);
    }

    @Override public Quote getQuote(Instrument instrument) {
        if (!connected) throw new ProviderException("mock market data disconnected");
        Instant now = clock.now();
        LocalDate d = session.dateOf(now);
        if (!session.isTradingDay(d) || now.isBefore(session.openInstant(d))) d = session.previousTradingDay(d);
        List<Candle> series = minuteSeries(instrument, d);
        if (series.isEmpty()) throw new ProviderException("no mock data for " + instrument.tradingSymbol());
        Candle ref = null;
        for (int k = series.size() - 1; k >= 0; k--) {
            if (!series.get(k).start().isAfter(now)) { ref = series.get(k); break; }
        }
        if (ref == null) ref = series.get(0);
        boolean done = !ref.end(1).isAfter(now);
        double last = done ? ref.close() : ref.open();
        double tick = instrument.tickSize().doubleValue();
        double halfSpread = instrument.isOption()
                ? Math.max(tick, last * 0.012) / 2.0
                : Math.max(tick, last * 0.0003) / 2.0;
        double bid = Math.max(tick, Math.floor((last - halfSpread) / tick) * tick);
        double ask = Math.ceil((last + halfSpread) / tick) * tick;
        long vol = series.stream().filter(c -> !c.start().isAfter(now)).mapToLong(Candle::volume).sum();
        Instant ts = session.isOpen(now) ? now : session.closeInstant(d);
        return new Quote(instrument.tradingSymbol(), last, bid, ask, 1000, 1000, vol,
                ref.openInterest(), ts.minus(quoteAgeOffset), SOURCE);
    }

    @Override public Map<String, Quote> getQuotes(List<Instrument> instruments) {
        Map<String, Quote> out = new HashMap<>();
        for (Instrument i : instruments) {
            try { out.put(i.tradingSymbol(), getQuote(i)); } catch (ProviderException ignored) { /* absent */ }
        }
        return out;
    }

    @Override public List<Candle> getCandles(Instrument instrument, int intervalMinutes, Instant from, Instant to) {
        if (!connected) throw new ProviderException("mock market data disconnected");
        List<Candle> minute = new java.util.ArrayList<>();
        LocalDate d = session.dateOf(from);
        LocalDate last = session.dateOf(to);
        while (!d.isAfter(last)) {
            if (session.isTradingDay(d)) {
                for (Candle c : minuteSeries(instrument, d)) {
                    if (!c.start().isBefore(from) && c.start().isBefore(to)) minute.add(c);
                }
            }
            d = d.plusDays(1);
        }
        return intervalMinutes <= 1 ? minute : CandleSeries.aggregate(minute, intervalMinutes, session, to);
    }

    @Override public boolean isConnected() { return connected; }
    @Override public String sourceName() { return SOURCE; }
}
