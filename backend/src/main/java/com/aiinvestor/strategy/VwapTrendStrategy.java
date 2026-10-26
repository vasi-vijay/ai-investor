package com.aiinvestor.strategy;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.indicators.Indicators;
import com.aiinvestor.indicators.IndicatorSet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy A — VWAP trend confirmation.
 * LONG: close above VWAP, EMA9 above EMA21 and rising, volume confirmation, RSI not exhausted, and the setup
 * is FRESH (it was not already true on the previous {@code freshLookback} bars) to avoid repeated signals.
 * SHORT mirrors. Evaluated on completed bars only.
 */
@Component
public class VwapTrendStrategy extends BaseStrategy {
    @Override public String name() { return "VWAP_TREND"; }
    @Override public String version() { return "1.0"; }

    @Override public Params defaultParams() {
        return Params.of(
                "enabled", true,
                "volumeMultiple", 1.0,        // last-bar volume >= multiple * 20-bar average volume
                "requireVolume", true,        // skipped automatically for instruments without volume (indices)
                "rsiLongMin", 50.0, "rsiLongMax", 75.0,
                "rsiShortMin", 25.0, "rsiShortMax", 50.0,
                "freshLookback", 2,           // alignment must NOT have held on any of this many preceding bars (0 disables)
                "minBarsToday", 4,
                "stopAtrMultiple", 1.5,
                "rewardMultiple", 2.0,
                "minRiskReward", 1.5,
                "allowShort", true);
    }

    @Override protected StrategyResult doEvaluate(CandleContext ctx, IndicatorSet ind, Params defaults) {
        Params p = defaultParams().merge(defaults);
        if (ctx.today().size() < p.i("minBarsToday", 4)) return StrategyResult.noTrade("too early in session for VWAP confirmation");
        List<String> sup = new ArrayList<>(), con = new ArrayList<>();
        Candle last = ctx.lastBar();
        boolean up = ind.close() > ind.vwap() && ind.ema9() > ind.ema21() && ind.ema9() > ind.ema9Prev();
        boolean down = ind.close() < ind.vwap() && ind.ema9() < ind.ema21() && ind.ema9() < ind.ema9Prev();
        if (!up && !down) {
            return StrategyResult.noTrade(List.of(), List.of("no VWAP/EMA alignment: close " + f(ind.close()) + ", VWAP " + f(ind.vwap())
                    + ", EMA9 " + f(ind.ema9()) + ", EMA21 " + f(ind.ema21())));
        }
        boolean isLong = up;
        if (!isLong && !p.b("allowShort", true)) return StrategyResult.noTrade("short side disabled by configuration");
        sup.add(isLong ? "close " + f(ind.close()) + " above VWAP " + f(ind.vwap()) : "close " + f(ind.close()) + " below VWAP " + f(ind.vwap()));
        sup.add("EMA9 " + f(ind.ema9()) + (isLong ? " above" : " below") + " EMA21 " + f(ind.ema21()) + " and " + (isLong ? "rising" : "falling"));
        if (!ind.vwapVolumeWeighted()) con.add("VWAP is an equal-weighted proxy (instrument has no volume)");

        // volume confirmation
        if (p.b("requireVolume", true) && ind.hasVolume()) {
            double need = p.d("volumeMultiple", 1.0) * ind.avgVolume20();
            if (last.volume() >= need) sup.add("volume " + last.volume() + " >= " + f(need) + " (x" + p.d("volumeMultiple", 1.0) + " avg20)");
            else return StrategyResult.noTrade(sup, List.of("volume " + last.volume() + " below confirmation threshold " + f(need)));
        } else if (!ind.hasVolume()) {
            con.add("volume confirmation not applicable (no volume data)");
        }

        // RSI band
        double rsi = ind.rsi14();
        double lo = isLong ? p.d("rsiLongMin", 50) : p.d("rsiShortMin", 25);
        double hi = isLong ? p.d("rsiLongMax", 75) : p.d("rsiShortMax", 50);
        if (rsi < lo || rsi > hi) return StrategyResult.noTrade(sup, List.of("RSI " + f(rsi) + " outside [" + f(lo) + ", " + f(hi) + "]"));
        sup.add("RSI " + f(rsi) + " within [" + f(lo) + ", " + f(hi) + "]");

        // freshness: avoid re-signalling a long-running alignment
        int fresh = p.i("freshLookback", 3);
        if (fresh > 0 && alignedRecently(ctx, isLong, fresh)) {
            return StrategyResult.noTrade(sup, List.of("setup not fresh: VWAP/EMA alignment already held within the previous " + fresh + " bar(s)"));
        }

        double atr = ind.atr14();
        double risk = p.d("stopAtrMultiple", 1.5) * atr;
        double entry = ind.close();
        double stop = isLong ? entry - risk : entry + risk;
        double target = isLong ? entry + p.d("rewardMultiple", 2.0) * risk : entry - p.d("rewardMultiple", 2.0) * risk;
        List<String> inv = List.of(isLong ? "close back below VWAP " + f(ind.vwap()) : "close back above VWAP " + f(ind.vwap()),
                isLong ? "EMA9 crosses below EMA21" : "EMA9 crosses above EMA21");
        return trade(isLong, entry, stop, target, ctx.instrument().tickSize().doubleValue(), p.d("minRiskReward", 1.5), sup, con, inv);
    }

    /** True if the same-direction alignment held on ANY of the previous n bars (so this is not a new trigger). */
    private boolean alignedRecently(CandleContext ctx, boolean isLong, int n) {
        List<Candle> all = ctx.candles();
        double[] closes = Indicators.closes(all);
        double[] e9 = Indicators.ema(closes, 9), e21 = Indicators.ema(closes, 21);
        Indicators.Vwap vw = Indicators.vwap(ctx.today());
        int base = ctx.todayStart();
        int last = all.size() - 1;
        for (int k = 1; k <= n; k++) {
            int idx = last - k;
            if (idx < base) continue;                        // before today's session: no VWAP, cannot be aligned
            double v = vw.values()[idx - base];
            boolean aligned = isLong ? (closes[idx] > v && e9[idx] > e21[idx]) : (closes[idx] < v && e9[idx] < e21[idx]);
            if (aligned) return true;
        }
        return false;
    }
}
