package com.aiinvestor.support;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.market.MarketSession;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Hand-crafted candle builders for deterministic strategy / broker tests. */
public final class Candles {
    private Candles() {}

    public static Candle c(Instant start, double o, double h, double l, double cl, long v) { return new Candle(start, o, h, l, cl, v); }

    /** n flat-ish bars starting at the session open of {@code day}, every {@code minutes}. */
    public static List<Candle> flat(MarketSession s, LocalDate day, int minutes, int n, double price, long vol) {
        List<Candle> out = new ArrayList<>();
        Instant t = s.openInstant(day);
        for (int i = 0; i < n; i++) {
            out.add(c(t, price, price + 0.1, price - 0.1, price, vol));
            t = t.plusSeconds(minutes * 60L);
        }
        return out;
    }

    /** previous trading day full of bars + today's bars (all completed), for CandleContext construction. */
    public static List<Candle> concat(List<Candle> a, List<Candle> b) {
        List<Candle> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
