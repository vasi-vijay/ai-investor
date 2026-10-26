package com.aiinvestor.engine;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Enums.Direction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic forward simulation of a candidate's levels over later 1-minute bars: what WOULD have happened to
 * the strategy's proposal, independent of whether it was traded. Cost-free, expressed in R (risk multiples).
 * Entry = open of the first bar after the signal; stop is assumed to fill first when a bar touches both levels.
 */
public final class OutcomeTracker {
    private OutcomeTracker() {}

    public record Outcome(String result, double rMultiple, double mfeR, double maeR, int bars, double entryFill, double exitPrice) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("result", result);
            m.put("r_multiple", round(rMultiple));
            m.put("mfe_r", round(mfeR));
            m.put("mae_r", round(maeR));
            m.put("bars_held", bars);
            m.put("entry_fill", entryFill);
            m.put("exit_price", exitPrice);
            return m;
        }
        private static double round(double v) { return Math.round(v * 1000d) / 1000d; }
    }

    /** @param bars 1-minute bars strictly after the signal bar, ascending, ending at the forced flat time. */
    public static Outcome simulate(Direction dir, double entryRef, double stop, double target, List<Candle> bars) {
        if (bars.isEmpty()) return new Outcome("NO_DATA", 0, 0, 0, 0, entryRef, entryRef);
        boolean isLong = dir == Direction.LONG;
        double entry = bars.get(0).open();
        double risk = Math.abs(entryRef - stop);
        if (risk <= 0) return new Outcome("NO_DATA", 0, 0, 0, 0, entry, entry);
        double mfe = 0, mae = 0;
        for (int i = 0; i < bars.size(); i++) {
            Candle b = bars.get(i);
            double fav = isLong ? b.high() - entry : entry - b.low();
            double adv = isLong ? entry - b.low() : b.high() - entry;
            boolean stopHit = isLong ? b.low() <= stop : b.high() >= stop;
            boolean targetHit = isLong ? b.high() >= target : b.low() <= target;
            if (stopHit) {
                double px = isLong ? Math.min(stop, b.open()) : Math.max(stop, b.open());
                mae = Math.max(mae, adv / risk);
                return new Outcome("STOP", (isLong ? px - entry : entry - px) / risk, mfe, mae, i + 1, entry, px);
            }
            mfe = Math.max(mfe, fav / risk);
            mae = Math.max(mae, adv / risk);
            if (targetHit) {
                double px = isLong ? Math.max(target, b.open()) : Math.min(target, b.open());
                return new Outcome("TARGET", (isLong ? px - entry : entry - px) / risk, mfe, mae, i + 1, entry, px);
            }
        }
        double last = bars.get(bars.size() - 1).close();
        return new Outcome("EOD", (isLong ? last - entry : entry - last) / risk, mfe, mae, bars.size(), entry, last);
    }
}
