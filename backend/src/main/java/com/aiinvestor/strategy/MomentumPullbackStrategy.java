package com.aiinvestor.strategy;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.indicators.Indicators;
import com.aiinvestor.indicators.IndicatorSet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy C — Momentum pullback.
 * Requires an established trend (EMA9 > EMA21 with most recent bars closing on the trend side of EMA21 and price
 * on the trend side of VWAP), a pullback that tested EMA21/VWAP, and a re-entry bar that closes back through the
 * prior bar's extreme in the trend direction. Stop sits beyond the pullback extreme.
 */
@Component
public class MomentumPullbackStrategy extends BaseStrategy {
    @Override public String name() { return "MOMENTUM_PULLBACK"; }
    @Override public String version() { return "1.0"; }

    @Override public Params defaultParams() {
        return Params.of(
                "enabled", true,
                "trendLookback", 12,
                "minTrendBars", 8,            // of the lookback bars, how many closed on the trend side of EMA21
                "pullbackLookback", 5,
                "pullbackBandAtr", 0.5,       // pullback low must come within this many ATR of EMA21 or VWAP
                "rsiPullbackLong", 55.0,      // RSI dipped below this during the pullback
                "rsiPullbackShort", 45.0,
                "stopBufferAtr", 0.2,
                "rewardMultiple", 2.0,
                "minRiskReward", 1.5,
                "minBarsToday", 8,
                "allowShort", true);
    }

    @Override protected StrategyResult doEvaluate(CandleContext ctx, IndicatorSet ind, Params defaults) {
        Params p = defaultParams().merge(defaults);
        List<Candle> all = ctx.candles();
        int n = all.size();
        if (ctx.today().size() < p.i("minBarsToday", 8)) return StrategyResult.noTrade("too early in session");
        int look = p.i("trendLookback", 12);
        int pb = p.i("pullbackLookback", 5);
        if (n < look + 2) return StrategyResult.insufficient("not enough bars for trend lookback");

        double[] closes = Indicators.closes(all);
        double[] e21 = Indicators.ema(closes, 21);
        double[] rsi = Indicators.rsi(closes, 14);
        Indicators.Vwap vw = Indicators.vwap(ctx.today());
        double atr = ind.atr14();

        boolean upTrend = ind.ema9() > ind.ema21() && ind.close() > ind.vwap();
        boolean downTrend = ind.ema9() < ind.ema21() && ind.close() < ind.vwap();
        if (!upTrend && !downTrend) return StrategyResult.noTrade("no established trend (EMA9/EMA21/VWAP not aligned)");
        boolean isLong = upTrend;
        if (!isLong && !p.b("allowShort", true)) return StrategyResult.noTrade("short side disabled by configuration");

        // established trend: majority of lookback closes on trend side of EMA21
        int onSide = 0;
        for (int k = n - look; k < n; k++) {
            if (Double.isNaN(e21[k])) continue;
            if (isLong ? closes[k] > e21[k] : closes[k] < e21[k]) onSide++;
        }
        List<String> sup = new ArrayList<>(), con = new ArrayList<>();
        if (onSide < p.i("minTrendBars", 8)) return StrategyResult.noTrade("trend not established: only " + onSide + "/" + look + " bars on trend side of EMA21");
        sup.add("established " + (isLong ? "up" : "down") + "trend: " + onSide + "/" + look + " closes on trend side of EMA21");

        // pullback within the last pb bars before the signal bar
        int sigIdx = n - 1;
        double extreme = isLong ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        boolean touched = false;
        double minRsi = 100, maxRsi = 0;
        int base = ctx.todayStart();
        for (int k = Math.max(0, sigIdx - pb); k < sigIdx; k++) {
            Candle c = all.get(k);
            double vwapK = k >= base ? vw.values()[k - base] : Double.NaN;
            double band = p.d("pullbackBandAtr", 0.5) * atr;
            double ref = isLong ? c.low() : c.high();
            boolean nearEma = !Double.isNaN(e21[k]) && Math.abs(ref - e21[k]) <= band || (!Double.isNaN(e21[k]) && (isLong ? ref <= e21[k] : ref >= e21[k]));
            boolean nearVwap = !Double.isNaN(vwapK) && (Math.abs(ref - vwapK) <= band || (isLong ? ref <= vwapK : ref >= vwapK));
            if (nearEma || nearVwap) touched = true;
            extreme = isLong ? Math.min(extreme, c.low()) : Math.max(extreme, c.high());
            if (!Double.isNaN(rsi[k])) { minRsi = Math.min(minRsi, rsi[k]); maxRsi = Math.max(maxRsi, rsi[k]); }
        }
        if (!touched) return StrategyResult.noTrade(sup, List.of("no pullback toward EMA21/VWAP in the last " + pb + " bars"));
        sup.add("pullback tested EMA21/VWAP within the last " + pb + " bars (extreme " + f(extreme) + ")");
        if (isLong ? minRsi >= p.d("rsiPullbackLong", 55) : maxRsi <= p.d("rsiPullbackShort", 45)) {
            return StrategyResult.noTrade(sup, List.of("RSI did not retrace during pullback (min " + f(minRsi) + ", max " + f(maxRsi) + ")"));
        }

        // re-entry confirmation on the signal bar
        Candle sig = all.get(sigIdx), prev = all.get(sigIdx - 1);
        boolean reentry = isLong ? (sig.close() > prev.high() && sig.close() > sig.open() && sig.close() > ind.ema9())
                                 : (sig.close() < prev.low() && sig.close() < sig.open() && sig.close() < ind.ema9());
        if (!reentry) return StrategyResult.noTrade(sup, List.of("no re-entry confirmation (needs close through prior bar's " + (isLong ? "high" : "low") + ", in trend direction, beyond EMA9)"));
        sup.add("re-entry bar closed through prior bar " + (isLong ? "high " + f(prev.high()) : "low " + f(prev.low())));

        double entry = sig.close();
        double buffer = p.d("stopBufferAtr", 0.2) * atr;
        double stop = isLong ? extreme - buffer : extreme + buffer;
        double risk = Math.abs(entry - stop);
        if (risk <= 0) return StrategyResult.noTrade(sup, List.of("degenerate stop distance"));
        double target = isLong ? entry + p.d("rewardMultiple", 2.0) * risk : entry - p.d("rewardMultiple", 2.0) * risk;
        List<String> inv = List.of(isLong ? "close below pullback low " + f(extreme) : "close above pullback high " + f(extreme),
                isLong ? "EMA9 crosses below EMA21" : "EMA9 crosses above EMA21");
        return trade(isLong, entry, stop, target, ctx.instrument().tickSize().doubleValue(), p.d("minRiskReward", 1.5), sup, con, inv);
    }
}
