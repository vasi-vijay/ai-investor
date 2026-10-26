package com.aiinvestor.market;

import com.aiinvestor.domain.Candle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure helpers for building look-ahead-free candle series. */
public final class CandleSeries {
    private CandleSeries() {}

    /** Only candles whose interval has fully elapsed at {@code now} (end <= now). */
    public static List<Candle> completedUpTo(List<Candle> candles, int intervalMinutes, Instant now) {
        List<Candle> out = new ArrayList<>(candles.size());
        for (Candle c : candles) {
            if (!c.end(intervalMinutes).isAfter(now)) out.add(c);
        }
        return out;
    }

    /**
     * Aggregate finer candles to {@code targetMinutes}, buckets aligned to the session open of each day.
     * Buckets are emitted only when their end is <= {@code now} (so a forming bar is never returned).
     */
    public static List<Candle> aggregate(List<Candle> base, int targetMinutes, MarketSession session, Instant now) {
        if (targetMinutes <= 1) return completedUpTo(base, 1, now);
        TreeMap<Instant, List<Candle>> buckets = new TreeMap<>();
        long stepSec = targetMinutes * 60L;
        for (Candle c : base) {
            Instant open = session.openInstant(session.dateOf(c.start()));
            long offset = Duration.between(open, c.start()).getSeconds();
            if (offset < 0) continue;
            Instant bucket = open.plusSeconds((offset / stepSec) * stepSec);
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(c);
        }
        List<Candle> out = new ArrayList<>(buckets.size());
        for (Map.Entry<Instant, List<Candle>> e : buckets.entrySet()) {
            Instant end = e.getKey().plusSeconds(stepSec);
            if (end.isAfter(now)) continue;
            List<Candle> g = e.getValue();
            double o = g.get(0).open();
            double h = Double.NEGATIVE_INFINITY, l = Double.POSITIVE_INFINITY;
            long vol = 0;
            for (Candle c : g) { h = Math.max(h, c.high()); l = Math.min(l, c.low()); vol += c.volume(); }
            Long oi = g.get(g.size() - 1).openInterest();
            out.add(new Candle(e.getKey(), o, h, l, g.get(g.size() - 1).close(), vol, oi));
        }
        return out;
    }
}
