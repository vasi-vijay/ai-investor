package com.aiinvestor.indicators;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.strategy.CandleContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Indicator values at the last completed bar, computed deterministically from the CandleContext. */
public record IndicatorSet(double close, double ema9, double ema21, double ema9Prev, double ema21Prev, double rsi14,
                           double atr14, double vwap, boolean vwapVolumeWeighted, double avgVolume20,
                           double lastVolume, double sessionHigh, double sessionLow, double atrPct,
                           String trend, String volatility, boolean hasVolume) {

    public static IndicatorSet compute(CandleContext ctx) {
        List<Candle> all = ctx.candles();
        double[] closes = Indicators.closes(all);
        double[] e9 = Indicators.ema(closes, 9);
        double[] e21 = Indicators.ema(closes, 21);
        double[] rsi = Indicators.rsi(closes, 14);
        double[] atr = Indicators.atr(all, 14);
        Indicators.Vwap vw = Indicators.vwap(ctx.today());
        int n = all.size();
        double close = closes[n - 1];
        double sh = Double.NEGATIVE_INFINITY, sl = Double.POSITIVE_INFINITY;
        for (Candle c : ctx.today()) { sh = Math.max(sh, c.high()); sl = Math.min(sl, c.low()); }
        double vwap = vw.values()[vw.values().length - 1];
        double e9l = Indicators.last(e9), e21l = Indicators.last(e21);
        double atrl = Indicators.last(atr);
        double atrPct = atrl / close;
        boolean hasVol = ctx.today().stream().anyMatch(c -> c.volume() > 0);
        String trend = classifyTrend(close, vwap, e9l, e21l);
        String vol = Double.isNaN(atrPct) ? "UNKNOWN" : atrPct < 0.0007 ? "LOW_VOL" : atrPct > 0.0018 ? "HIGH_VOL" : "NORMAL_VOL";
        return new IndicatorSet(close, e9l, e21l, n >= 2 ? e9[n - 2] : Double.NaN, n >= 2 ? e21[n - 2] : Double.NaN,
                Indicators.last(rsi), atrl, vwap, vw.volumeWeighted(),
                Indicators.average(all, n - 20, n), all.get(n - 1).volume(), sh, sl, atrPct, trend, vol, hasVol);
    }

    static String classifyTrend(double close, double vwap, double e9, double e21) {
        if (Double.isNaN(e9) || Double.isNaN(e21)) return "UNKNOWN";
        if (close > vwap && e9 > e21) return "TREND_UP";
        if (close < vwap && e9 < e21) return "TREND_DOWN";
        return "RANGE";
    }

    public String regime() { return trend + "/" + volatility; }

    public boolean complete() {
        return !(Double.isNaN(ema9) || Double.isNaN(ema21) || Double.isNaN(rsi14) || Double.isNaN(atr14) || Double.isNaN(vwap));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("close", round(close));
        m.put("ema9", round(ema9));
        m.put("ema21", round(ema21));
        m.put("rsi14", round(rsi14));
        m.put("atr14", round(atr14));
        m.put("atr_pct", Double.isNaN(atrPct) ? null : Math.round(atrPct * 1e6) / 1e6);
        m.put("vwap", round(vwap));
        m.put("vwap_is_volume_weighted", vwapVolumeWeighted);
        m.put("avg_volume_20", hasVolume ? round(avgVolume20) : null);
        m.put("last_volume", hasVolume ? lastVolume : null);
        m.put("session_high", round(sessionHigh));
        m.put("session_low", round(sessionLow));
        m.put("trend", trend);
        m.put("volatility", volatility);
        return m;
    }

    private static Double round(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? null : Math.round(v * 10000d) / 10000d; }
}
