package com.aiinvestor.strategy;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Enums.Decision;
import com.aiinvestor.domain.Enums.InstrumentType;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.TimeFrame;
import com.aiinvestor.indicators.IndicatorSet;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.mock.SyntheticMarket;
import com.aiinvestor.support.Candles;
import com.aiinvestor.support.TestProps;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StrategiesTest {
    private final AppProperties props = TestProps.defaults();
    private final MarketSession session = new MarketSession(props.market());
    private final Instrument equity = new Instrument(1L, "NSE", "CASH", InstrumentType.EQUITY, "TEST", "NSE-TEST", "Test", null, null, null,
            null, 1, new BigDecimal("0.05"), "T", true, "TEST");
    private static final LocalDate PREV = LocalDate.of(2025, 3, 3), TODAY = LocalDate.of(2025, 3, 4);

    private CandleContext ctx(List<Candle> all, int todayCount) {
        int todayStart = all.size() - todayCount;
        Candle last = all.get(all.size() - 1);
        return new CandleContext(equity, new TimeFrame(5), last.end(5), all, todayStart, session, null);
    }

    private List<Candle> warmup() { return Candles.flat(session, PREV, 5, 60, 100.0, 10_000); }

    /** Alternating +0.5 / -0.3 closes: a gentle noisy up-trend with RSI around 60. */
    private List<Candle> noisyUptrend(int n) {
        List<Candle> out = new ArrayList<>();
        Instant t = session.openInstant(TODAY);
        double prev = 100.0;
        for (int i = 0; i < n; i++) {
            double close = prev + (i % 2 == 0 ? 0.5 : -0.3);
            out.add(Candles.c(t, prev, Math.max(prev, close) + 0.1, Math.min(prev, close) - 0.1, close, 30_000));
            prev = close;
            t = t.plusSeconds(300);
        }
        return out;
    }

    /** A V-shaped day: noisy decline for 20 bars (no long alignment), then a noisy recovery - a genuine fresh trigger. */
    private List<Candle> flatThenUptrend() {
        List<Candle> out = new ArrayList<>();
        Instant t = session.openInstant(TODAY);
        double prev = 100.0;
        for (int i = 0; i < 70; i++) {
            boolean down = i < 20;
            double step = down ? (i % 2 == 0 ? -0.5 : 0.3) : (i % 2 == 0 ? 0.6 : -0.3);
            double close = prev + step;
            out.add(Candles.c(t, prev, Math.max(prev, close) + 0.1, Math.min(prev, close) - 0.1, close, 30_000));
            prev = close;
            t = t.plusSeconds(300);
        }
        return out;
    }

    @Test
    void vwapTrendEmitsFreshLongSignalsWithOrderedLevels() {
        VwapTrendStrategy s = new VwapTrendStrategy();
        List<Candle> today = flatThenUptrend();
        int signals = 0;
        boolean previousWasSignal = false;
        for (int k = 4; k <= today.size(); k++) {
            List<Candle> all = Candles.concat(warmup(), today.subList(0, k));
            CandleContext c = ctx(all, k);
            StrategyResult r = s.evaluate(c, IndicatorSet.compute(c), s.defaultParams());
            if (r.decision() == Decision.BUY_CANDIDATE) {
                signals++;
                assertThat(r.stop()).isLessThan(r.entry());
                assertThat(r.entry()).isLessThan(r.target());
                assertThat(r.entry()).isCloseTo(all.get(all.size() - 1).close(), org.assertj.core.api.Assertions.within(0.025));   // tick-rounded
                assertThat(r.riskReward()).isGreaterThanOrEqualTo(1.5);
                assertThat(r.supporting()).isNotEmpty();
                assertThat(r.invalidation()).isNotEmpty();
                assertThat(previousWasSignal).as("freshness filter prevents back-to-back signals").isFalse();
                previousWasSignal = true;
            } else previousWasSignal = false;
        }
        assertThat(signals).isGreaterThanOrEqualTo(1);
        assertThat(signals).isLessThan(20);
    }

    @Test
    void vwapTrendReturnsNoTradeOnFlatMarket() {
        VwapTrendStrategy s = new VwapTrendStrategy();
        List<Candle> all = Candles.concat(warmup(), Candles.flat(session, TODAY, 5, 20, 100.0, 10_000));
        CandleContext c = ctx(all, 20);
        StrategyResult r = s.evaluate(c, IndicatorSet.compute(c), s.defaultParams());
        assertThat(r.decision()).isEqualTo(Decision.NO_TRADE);
        assertThat(r.contradicting()).isNotEmpty();
    }

    @Test
    void insufficientHistoryYieldsInsufficientData() {
        VwapTrendStrategy s = new VwapTrendStrategy();
        List<Candle> all = Candles.flat(session, TODAY, 5, 5, 100.0, 1000);
        CandleContext c = ctx(all, 5);
        StrategyResult r = s.evaluate(c, IndicatorSet.compute(c), s.defaultParams());
        assertThat(r.decision()).isEqualTo(Decision.INSUFFICIENT_DATA);
    }

    private List<Candle> orbDay(double[] closes, long[] vols) {
        List<Candle> out = new ArrayList<>();
        Instant t = session.openInstant(TODAY);
        double prev = 100.5;
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            double h = i < 3 ? 101.0 : Math.max(prev, c) + 0.1;
            double l = i < 3 ? 99.9 : Math.min(prev, c) - 0.1;
            out.add(Candles.c(t, prev, h, l, c, vols[i]));
            prev = c;
            t = t.plusSeconds(300);
        }
        return out;
    }

    @Test
    void openingRangeBreakoutFiresOnceAndSuppressesDuplicates() {
        OpeningRangeBreakoutStrategy s = new OpeningRangeBreakoutStrategy();
        double[] closes = {100.5, 100.6, 100.4, 100.8, 101.5, 101.7};
        long[] vols = {20_000, 20_000, 20_000, 20_000, 40_000, 40_000};

        // before the opening range completes -> no trade
        List<Candle> early = Candles.concat(warmup(), orbDay(closes, vols).subList(0, 2));
        CandleContext ce = ctx(early, 2);
        assertThat(s.evaluate(ce, IndicatorSet.compute(ce), s.defaultParams()).decision()).isEqualTo(Decision.NO_TRADE);

        // first completed close beyond the range -> BUY with valid levels
        List<Candle> breakout = Candles.concat(warmup(), orbDay(closes, vols).subList(0, 5));
        CandleContext cb = ctx(breakout, 5);
        StrategyResult r = s.evaluate(cb, IndicatorSet.compute(cb), s.defaultParams());
        assertThat(r.decision()).isEqualTo(Decision.BUY_CANDIDATE);
        assertThat(r.stop()).isLessThan(r.entry());
        assertThat(r.target()).isGreaterThan(r.entry());
        assertThat(r.supporting().toString()).contains("opening range");

        // the next bar also closes above the range: duplicate must be suppressed
        List<Candle> next = Candles.concat(warmup(), orbDay(closes, vols));
        CandleContext cn = ctx(next, 6);
        StrategyResult dup = s.evaluate(cn, IndicatorSet.compute(cn), s.defaultParams());
        assertThat(dup.decision()).isEqualTo(Decision.NO_TRADE);
        assertThat(dup.contradicting().toString()).contains("duplicate");
    }

    @Test
    void openingRangeBreakoutRejectsLowVolumeBreakout() {
        OpeningRangeBreakoutStrategy s = new OpeningRangeBreakoutStrategy();
        double[] closes = {100.5, 100.6, 100.4, 100.8, 101.5};
        long[] vols = {20_000, 20_000, 20_000, 20_000, 5_000};
        List<Candle> all = Candles.concat(warmup(), orbDay(closes, vols));
        CandleContext c = ctx(all, 5);
        StrategyResult r = s.evaluate(c, IndicatorSet.compute(c), s.defaultParams());
        assertThat(r.decision()).isEqualTo(Decision.NO_TRADE);
        assertThat(r.contradicting().toString()).contains("volume");
    }

    @Test
    void parametersAreConfigurable() {
        VwapTrendStrategy s = new VwapTrendStrategy();
        List<Candle> today = noisyUptrend(40);
        int strict = 0, loose = 0;
        for (int k = 6; k <= today.size(); k++) {
            CandleContext c = ctx(Candles.concat(warmup(), today.subList(0, k)), k);
            IndicatorSet ind = IndicatorSet.compute(c);
            if (s.evaluate(c, ind, s.defaultParams().merge(Params.of("rsiLongMax", 52.0))).decision() == Decision.BUY_CANDIDATE) strict++;
            if (s.evaluate(c, ind, s.defaultParams().merge(Params.of("rsiLongMax", 99.0, "freshLookback", 0))).decision() == Decision.BUY_CANDIDATE) loose++;
        }
        assertThat(loose).isGreaterThan(strict);
    }

    @Test
    void allStrategiesObeyInvariantsAcrossManySyntheticDays() {
        SyntheticMarket market = new SyntheticMarket(session, null, null);
        List<Strategy> all = List.of(new VwapTrendStrategy(), new OpeningRangeBreakoutStrategy(), new MomentumPullbackStrategy());
        int candidates = 0, evaluations = 0;
        LocalDate d = LocalDate.of(2025, 3, 3);
        for (int day = 0; day < 8; day++) {
            d = session.nextTradingDay(d);
            for (String sym : List.of("RELIANCE", "TCS")) {
                List<Candle> minute = new ArrayList<>(market.minuteCandles(sym, session.previousTradingDay(d)));
                minute.addAll(market.minuteCandles(sym, d));
                List<Candle> five = com.aiinvestor.market.CandleSeries.aggregate(minute, 5, session, Instant.MAX);
                int todayStart = 0;
                while (!session.dateOf(five.get(todayStart).start()).equals(d)) todayStart++;
                Instrument ins = new Instrument(2L, "NSE", "CASH", InstrumentType.EQUITY, sym, sym, sym, null, null, null, null, 1, new BigDecimal("0.05"), "x", true, "TEST");
                for (int k = todayStart + 4; k < five.size(); k += 3) {
                    List<Candle> sub = five.subList(0, k + 1);
                    CandleContext c = new CandleContext(ins, new TimeFrame(5), sub.get(k).end(5), sub, todayStart, session, null);
                    IndicatorSet ind = IndicatorSet.compute(c);
                    for (Strategy s : all) {
                        StrategyResult r = s.evaluate(c, ind, s.defaultParams());
                        evaluations++;
                        if (r.isCandidate()) {
                            candidates++;
                            boolean buy = r.decision() == Decision.BUY_CANDIDATE;
                            assertThat(buy ? r.stop() < r.entry() && r.entry() < r.target() : r.target() < r.entry() && r.entry() < r.stop())
                                    .as("%s levels ordered", s.name()).isTrue();
                            assertThat(r.riskReward()).isGreaterThanOrEqualTo(1.29);
                        } else {
                            assertThat(r.entry()).isNull();
                        }
                    }
                }
            }
        }
        assertThat(evaluations).isGreaterThan(100);
        assertThat(candidates).isGreaterThan(0);
    }
}
