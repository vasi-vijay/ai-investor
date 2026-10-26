package com.aiinvestor.strategy;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.indicators.IndicatorSet;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Strategy B — Opening Range Breakout.
 * The opening range (OR) is the high/low of the first {@code orMinutes}. A signal fires on the FIRST completed bar
 * that closes beyond the OR by a buffer; later breakouts in the same direction that day are suppressed
 * (stateless duplicate prevention: any earlier bar that already closed beyond the OR disqualifies this one).
 */
@Component
public class OpeningRangeBreakoutStrategy extends BaseStrategy {
    @Override public String name() { return "OPENING_RANGE_BREAKOUT"; }
    @Override public String version() { return "1.0"; }

    @Override public Params defaultParams() {
        return Params.of(
                "enabled", true,
                "orMinutes", 15,
                "breakoutBufferPct", 0.0005,   // close must exceed OR edge by this fraction
                "volumeMultiple", 1.2,         // breakout-bar volume vs average OR-bar volume
                "requireVolume", true,
                "minRangePct", 0.0015, "maxRangePct", 0.015,
                "lastEntryTime", "11:30",      // exchange-local time; no fresh breakouts after this
                "stopMode", "OR_OPPOSITE",     // OR_OPPOSITE | OR_MID | ATR
                "stopAtrMultiple", 1.5,
                "maxStopAtrMultiple", 3.0,
                "rewardMultiple", 1.5,
                "minRiskReward", 1.3,
                "allowShort", true);
    }

    @Override protected StrategyResult doEvaluate(CandleContext ctx, IndicatorSet ind, Params defaults) {
        Params p = defaultParams().merge(defaults);
        int tf = ctx.timeframe().minutes();
        int orMin = p.i("orMinutes", 15);
        Instant open = ctx.session().openInstant(ctx.session().dateOf(ctx.asOf()));
        Instant orEnd = open.plus(Duration.ofMinutes(orMin));
        List<Candle> today = ctx.today();
        List<Candle> orBars = new ArrayList<>();
        for (Candle c : today) if (c.start().isBefore(orEnd)) orBars.add(c);
        // OR must be fully formed from completed bars
        if (orBars.isEmpty() || orBars.get(orBars.size() - 1).end(tf).isBefore(orEnd)) {
            return StrategyResult.noTrade("opening range not yet complete");
        }
        double orHigh = Double.NEGATIVE_INFINITY, orLow = Double.POSITIVE_INFINITY, orVolSum = 0;
        for (Candle c : orBars) { orHigh = Math.max(orHigh, c.high()); orLow = Math.min(orLow, c.low()); orVolSum += c.volume(); }
        double orAvgVol = orVolSum / orBars.size();
        double width = (orHigh - orLow) / ind.close();
        if (width < p.d("minRangePct", 0.0015)) return StrategyResult.noTrade("opening range too narrow (" + pct(width) + ")");
        if (width > p.d("maxRangePct", 0.015)) return StrategyResult.noTrade("opening range too wide (" + pct(width) + ")");

        Candle last = ctx.lastBar();
        if (last.start().isBefore(orEnd)) return StrategyResult.noTrade("last bar is inside the opening range");
        LocalTime local = ctx.lastBarEnd().atZone(ctx.session().zone()).toLocalTime();
        if (local.isAfter(LocalTime.parse(p.s("lastEntryTime", "11:30")))) return StrategyResult.noTrade("past last ORB entry time " + p.s("lastEntryTime", "11:30"));

        double buf = p.d("breakoutBufferPct", 0.0005);
        double upLevel = orHigh * (1 + buf), dnLevel = orLow * (1 - buf);
        boolean longBreak = last.close() > upLevel;
        boolean shortBreak = last.close() < dnLevel;
        if (!longBreak && !shortBreak) {
            return StrategyResult.noTrade(List.of("OR " + f(orLow) + "–" + f(orHigh)),
                    List.of("close " + f(last.close()) + " has not broken OR edge (up " + f(upLevel) + ", down " + f(dnLevel) + ")"));
        }
        boolean isLong = longBreak;
        if (!isLong && !p.b("allowShort", true)) return StrategyResult.noTrade("short side disabled by configuration");

        // duplicate prevention: an earlier completed bar already closed beyond the edge in this direction
        for (Candle c : today) {
            if (!c.start().isBefore(last.start())) break;
            if (c.start().isBefore(orEnd)) continue;
            if (isLong ? c.close() > upLevel : c.close() < dnLevel) {
                return StrategyResult.noTrade("duplicate: breakout in this direction already signalled earlier today");
            }
        }
        List<String> sup = new ArrayList<>(), con = new ArrayList<>();
        sup.add("opening range " + f(orLow) + "–" + f(orHigh) + " (" + orMin + "m), width " + pct(width));
        sup.add("first completed close beyond OR " + (isLong ? "high" : "low") + ": " + f(last.close()));

        if (p.b("requireVolume", true) && ind.hasVolume()) {
            double need = p.d("volumeMultiple", 1.2) * orAvgVol;
            if (last.volume() >= need) sup.add("breakout volume " + last.volume() + " >= " + f(need));
            else return StrategyResult.noTrade(sup, List.of("breakout volume " + last.volume() + " below " + f(need)));
        } else if (!ind.hasVolume()) {
            con.add("volume confirmation not applicable (no volume data)");
        }
        if (isLong ? ind.close() < ind.vwap() : ind.close() > ind.vwap()) {
            con.add("price on the wrong side of VWAP for this breakout direction");
        }

        double entry = last.close();
        double atr = ind.atr14();
        double stop;
        switch (p.s("stopMode", "OR_OPPOSITE")) {
            case "OR_MID" -> stop = (orHigh + orLow) / 2;
            case "ATR" -> stop = isLong ? entry - p.d("stopAtrMultiple", 1.5) * atr : entry + p.d("stopAtrMultiple", 1.5) * atr;
            default -> stop = isLong ? orLow : orHigh;
        }
        double maxRisk = p.d("maxStopAtrMultiple", 3.0) * atr;
        if (Math.abs(entry - stop) > maxRisk) {
            stop = isLong ? entry - maxRisk : entry + maxRisk;
            con.add("stop capped at " + p.d("maxStopAtrMultiple", 3.0) + " ATR");
        }
        double risk = Math.abs(entry - stop);
        double target = isLong ? entry + p.d("rewardMultiple", 1.5) * risk : entry - p.d("rewardMultiple", 1.5) * risk;
        List<String> inv = List.of(isLong ? "close back inside OR (below " + f(orHigh) + ")" : "close back inside OR (above " + f(orLow) + ")");
        return trade(isLong, entry, stop, target, ctx.instrument().tickSize().doubleValue(), p.d("minRiskReward", 1.3), sup, con, inv);
    }

    private static String pct(double v) { return String.format(java.util.Locale.ROOT, "%.2f%%", v * 100); }
}
