package com.aiinvestor.strategy;

import com.aiinvestor.domain.Enums.Decision;
import com.aiinvestor.domain.Money;
import com.aiinvestor.indicators.IndicatorSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

abstract class BaseStrategy implements Strategy {

    @Override public final StrategyResult evaluate(CandleContext ctx, IndicatorSet ind, Params defaults) {
        if (ctx.candles().size() < 2 || ctx.today().isEmpty()) return StrategyResult.insufficient("not enough completed bars");
        if (!ind.complete()) return StrategyResult.insufficient("indicators not warmed up (need more history)");
        return doEvaluate(ctx, ind, defaults.merge(null));
    }

    protected abstract StrategyResult doEvaluate(CandleContext ctx, IndicatorSet ind, Params p);

    /** Validated trade candidate; returns NO_TRADE if the levels are on the wrong side or degenerate. */
    protected StrategyResult trade(boolean isLong, double entry, double stop, double target, double tick,
                                   double minRr, List<String> supporting, List<String> contradicting,
                                   List<String> invalidation) {
        entry = Money.roundToTick(entry, tick);
        stop = Money.roundToTick(stop, tick);
        target = Money.roundToTick(target, tick);
        boolean ordered = isLong ? (stop < entry && entry < target) : (target < entry && entry < stop);
        if (!ordered) {
            List<String> c = new ArrayList<>(contradicting);
            c.add(String.format(Locale.ROOT, "levels invalid after tick rounding (entry %.2f stop %.2f target %.2f)", entry, stop, target));
            return StrategyResult.noTrade(supporting, c);
        }
        double rr = Math.abs(target - entry) / Math.abs(entry - stop);
        if (rr < minRr) {
            List<String> c = new ArrayList<>(contradicting);
            c.add(String.format(Locale.ROOT, "risk/reward %.2f below strategy minimum %.2f", rr, minRr));
            return StrategyResult.noTrade(supporting, c);
        }
        return new StrategyResult(isLong ? Decision.BUY_CANDIDATE : Decision.SELL_CANDIDATE, entry, stop, target,
                supporting, contradicting, invalidation);
    }

    protected static String f(double v) { return String.format(Locale.ROOT, "%.2f", v); }
}
