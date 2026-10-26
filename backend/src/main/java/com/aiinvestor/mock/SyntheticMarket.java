package com.aiinvestor.mock;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.market.MarketSession;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic synthetic market. Everything produced here is FABRICATED test data: the same (symbol, date)
 * always yields the same 1-minute path, so tests and backtest comparisons are reproducible.
 * Day regimes (trend up/down, range, volatile) make the strategies fire occasionally.
 */
public class SyntheticMarket {
    public enum Regime { TREND_UP, TREND_DOWN, RANGE, VOLATILE }

    private static final LocalDate ANCHOR = LocalDate.of(2024, 1, 1);
    private static final double RISK_FREE = 0.065;

    private final MarketSession session;
    private final Map<String, Double> basePrices;
    private final Map<String, Integer> lotSizes;
    private final Map<String, List<Candle>> cache = new ConcurrentHashMap<>();

    public SyntheticMarket(MarketSession session, Map<String, Double> basePrices, Map<String, Integer> lotSizes) {
        this.session = session;
        this.basePrices = basePrices == null ? Map.of() : basePrices;
        this.lotSizes = lotSizes == null ? Map.of() : lotSizes;
    }

    public double basePrice(String symbol) {
        Double p = basePrices.get(symbol);
        if (p != null) return p;
        return switch (symbol) {
            case "NIFTY" -> 24500;
            case "BANKNIFTY" -> 52000;
            case "RELIANCE" -> 2900;
            case "TCS" -> 3800;
            case "INFY" -> 1600;
            case "HDFCBANK" -> 1700;
            default -> 500 + new SplittableRandom(symbol.hashCode()).nextInt(2500);
        };
    }

    public int lotSize(String underlying) {
        Integer l = lotSizes.get(underlying);
        if (l != null) return l;
        return switch (underlying) {
            case "NIFTY" -> 75;
            case "BANKNIFTY" -> 30;
            default -> 1;
        };
    }

    public static boolean isIndex(String symbol) { return symbol.equals("NIFTY") || symbol.equals("BANKNIFTY"); }

    public Regime regime(String symbol, LocalDate date) {
        double r = rng(symbol, date, 11).nextDouble();
        if (r < 0.28) return Regime.TREND_UP;
        if (r < 0.56) return Regime.TREND_DOWN;
        if (r < 0.86) return Regime.RANGE;
        return Regime.VOLATILE;
    }

    /** Day-open level following a deterministic daily random walk anchored at the base price. */
    public double dayOpen(String symbol, LocalDate date) {
        double level = Math.log(basePrice(symbol));
        LocalDate d = ANCHOR;
        int k = 0;
        while (d.isBefore(date)) {
            if (session.isTradingDay(d)) {
                level += 0.006 * gauss(rng(symbol, d, 7 + (k++ % 3)));
            }
            d = d.plusDays(1);
        }
        return Math.exp(level);
    }

    /** Full-session 1-minute candles for the underlying (index or equity). */
    public List<Candle> minuteCandles(String symbol, LocalDate date) {
        return cache.computeIfAbsent(symbol + "|" + date, k -> {
            if (cache.size() > 400) cache.clear();
            return buildMinuteCandles(symbol, date);
        });
    }

    private List<Candle> buildMinuteCandles(String symbol, LocalDate date) {
        List<Candle> out = new ArrayList<>(375);
        if (!session.isTradingDay(date)) return out;
        Regime regime = regime(symbol, date);
        SplittableRandom rnd = rng(symbol, date, 3);
        double sigma = switch (regime) { case VOLATILE -> 0.0009; case RANGE -> 0.00035; default -> 0.0004; };
        double drift = switch (regime) { case TREND_UP -> 0.00004; case TREND_DOWN -> -0.00004; default -> 0.0; };
        double open0 = dayOpen(symbol, date);
        double price = open0;
        double prevRet = 0;
        boolean index = isIndex(symbol);
        double baseVol = index ? 0 : 6000 * (0.7 + 0.6 * new SplittableRandom(symbol.hashCode()).nextDouble());
        Instant t = session.openInstant(date);
        Instant end = session.closeInstant(date);
        int minute = 0;
        while (t.isBefore(end)) {
            double ret = drift + 0.18 * prevRet + sigma * gauss(rnd);
            if (regime == Regime.RANGE) ret -= 0.015 * Math.log(price / open0);
            double o = price;
            double c = o * Math.exp(ret);
            double wick = Math.abs(sigma * gauss(rnd)) * 0.6;
            double h = Math.max(o, c) * (1 + wick);
            double l = Math.min(o, c) * (1 - wick);
            double tick = tickFor(symbol);
            o = roundTick(o, tick); c = roundTick(c, tick); h = roundTick(h, tick); l = roundTick(l, tick);
            h = Math.max(h, Math.max(o, c)); l = Math.min(l, Math.min(o, c));
            double shape = 1.0 + 1.6 * Math.exp(-minute / 25.0) + 1.0 * Math.exp(-(374 - minute) / 25.0);
            long vol = index ? 0 : Math.max(1, Math.round(baseVol * shape * Math.exp(0.35 * gauss(rnd))
                    * (regime == Regime.VOLATILE ? 1.6 : 1.0) * (1 + 40 * Math.abs(ret))));
            out.add(new Candle(t, o, h, l, c, vol));
            price = c;
            prevRet = ret;
            t = t.plus(Duration.ofMinutes(1));
            minute++;
        }
        return out;
    }

    public double tickFor(String symbol) { return 0.05; }

    /** Synthetic option 1-minute candles priced off the underlying minute path (Black-Scholes, smile, fixed IV). */
    public List<Candle> optionMinuteCandles(Instrument opt, LocalDate date) {
        List<Candle> under = minuteCandles(opt.underlying(), date);
        List<Candle> out = new ArrayList<>(under.size());
        boolean call = opt.optionType() == com.aiinvestor.domain.Enums.OptionType.CE;
        double k = opt.strike().doubleValue();
        Instant expiryClose = session.closeInstant(opt.expiry());
        SplittableRandom rnd = rng(opt.tradingSymbol(), date, 5);
        for (Candle u : under) {
            double po = optPrice(call, u.open(), k, expiryClose, u.start());
            double pc = optPrice(call, u.close(), k, expiryClose, u.start().plusSeconds(60));
            double ph = optPrice(call, call ? u.high() : u.low(), k, expiryClose, u.start().plusSeconds(30));
            double pl = optPrice(call, call ? u.low() : u.high(), k, expiryClose, u.start().plusSeconds(30));
            double hi = Math.max(Math.max(po, pc), Math.max(ph, pl));
            double lo = Math.min(Math.min(po, pc), Math.min(ph, pl));
            double tick = 0.05;
            double h = roundTick(hi, tick), l = Math.max(tick, roundTick(lo, tick));
            double o = Math.max(tick, roundTick(po, tick)), c = Math.max(tick, roundTick(pc, tick));
            h = Math.max(h, Math.max(o, c)); l = Math.min(l, Math.min(o, c));
            double moneyness = Math.abs(Math.log(u.close() / k));
            long vol = Math.round(opt.lotSize() * 12 * Math.exp(-moneyness * 60) * (0.5 + rnd.nextDouble()));
            long oi = Math.round(opt.lotSize() * 4000 * Math.exp(-moneyness * 40));
            out.add(new Candle(u.start(), o, h, l, c, vol, oi));
        }
        return out;
    }

    private double optPrice(boolean call, double spot, double k, Instant expiryClose, Instant at) {
        double tYears = Math.max(0, Duration.between(at, expiryClose).getSeconds()) / (365.0 * 24 * 3600);
        double iv = 0.14 + 0.9 * Math.abs(Math.log(k / spot)) ;
        return BlackScholes.price(call, spot, k, tYears, RISK_FREE, Math.min(iv, 0.8));
    }

    private static double roundTick(double v, double tick) { return Math.round(v / tick) * tick; }

    private static SplittableRandom rng(String key, LocalDate date, int salt) {
        long h = key.hashCode() * 1_000_003L + date.toEpochDay() * 7919L + salt * 104_729L;
        return new SplittableRandom(h);
    }

    private static double gauss(SplittableRandom r) {
        double u1 = Math.max(1e-12, r.nextDouble());
        double u2 = r.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }
}
