package com.aiinvestor.indicators;

import com.aiinvestor.domain.Candle;

import java.util.Arrays;
import java.util.List;

/** Deterministic indicator maths. Leading values with insufficient history are NaN (never fabricated). */
public final class Indicators {
    private Indicators() {}

    public static double[] closes(List<Candle> c) {
        double[] a = new double[c.size()];
        for (int i = 0; i < a.length; i++) a[i] = c.get(i).close();
        return a;
    }

    /** EMA seeded with the SMA of the first {@code period} values; NaN before that. */
    public static double[] ema(double[] v, int period) {
        double[] out = new double[v.length];
        Arrays.fill(out, Double.NaN);
        if (period <= 0 || v.length < period) return out;
        double sum = 0;
        for (int i = 0; i < period; i++) sum += v[i];
        double e = sum / period;
        out[period - 1] = e;
        double k = 2.0 / (period + 1);
        for (int i = period; i < v.length; i++) {
            e = v[i] * k + e * (1 - k);
            out[i] = e;
        }
        return out;
    }

    public static double[] sma(double[] v, int period) {
        double[] out = new double[v.length];
        Arrays.fill(out, Double.NaN);
        double sum = 0;
        for (int i = 0; i < v.length; i++) {
            sum += v[i];
            if (i >= period) sum -= v[i - period];
            if (i >= period - 1) out[i] = sum / period;
        }
        return out;
    }

    /** Wilder RSI. */
    public static double[] rsi(double[] v, int period) {
        double[] out = new double[v.length];
        Arrays.fill(out, Double.NaN);
        if (v.length <= period) return out;
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double d = v[i] - v[i - 1];
            if (d >= 0) gain += d; else loss -= d;
        }
        double avgGain = gain / period, avgLoss = loss / period;
        out[period] = rsiValue(avgGain, avgLoss);
        for (int i = period + 1; i < v.length; i++) {
            double d = v[i] - v[i - 1];
            double g = Math.max(d, 0), l = Math.max(-d, 0);
            avgGain = (avgGain * (period - 1) + g) / period;
            avgLoss = (avgLoss * (period - 1) + l) / period;
            out[i] = rsiValue(avgGain, avgLoss);
        }
        return out;
    }

    private static double rsiValue(double avgGain, double avgLoss) {
        if (avgLoss == 0) return avgGain == 0 ? 50.0 : 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - 100.0 / (1.0 + rs);
    }

    /** Wilder ATR over candles. */
    public static double[] atr(List<Candle> c, int period) {
        int n = c.size();
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        if (n <= period) return out;
        double[] tr = new double[n];
        tr[0] = c.get(0).high() - c.get(0).low();
        for (int i = 1; i < n; i++) {
            double pc = c.get(i - 1).close();
            tr[i] = Math.max(c.get(i).high() - c.get(i).low(),
                    Math.max(Math.abs(c.get(i).high() - pc), Math.abs(c.get(i).low() - pc)));
        }
        double sum = 0;
        for (int i = 1; i <= period; i++) sum += tr[i];
        double a = sum / period;
        out[period] = a;
        for (int i = period + 1; i < n; i++) {
            a = (a * (period - 1) + tr[i]) / period;
            out[i] = a;
        }
        return out;
    }

    /**
     * Cumulative VWAP over {@code candles} (caller passes a single session). Uses typical price (H+L+C)/3.
     * When the instrument carries no volume (e.g. an index), falls back to the equal-weighted cumulative typical
     * price and reports volumeWeighted=false so callers can disclose it.
     */
    public static Vwap vwap(List<Candle> session) {
        double[] out = new double[session.size()];
        double pv = 0, vol = 0, tpSum = 0;
        long totalVol = 0;
        for (Candle c : session) totalVol += c.volume();
        boolean weighted = totalVol > 0;
        for (int i = 0; i < session.size(); i++) {
            Candle c = session.get(i);
            double tp = (c.high() + c.low() + c.close()) / 3.0;
            if (weighted) {
                pv += tp * c.volume();
                vol += c.volume();
                out[i] = vol > 0 ? pv / vol : tp;
            } else {
                tpSum += tp;
                out[i] = tpSum / (i + 1);
            }
        }
        return new Vwap(out, weighted);
    }

    public record Vwap(double[] values, boolean volumeWeighted) {}

    public static double last(double[] a) { return a.length == 0 ? Double.NaN : a[a.length - 1]; }

    public static double average(List<Candle> c, int from, int to) {
        double s = 0; int n = 0;
        for (int i = Math.max(0, from); i < Math.min(c.size(), to); i++) { s += c.get(i).volume(); n++; }
        return n == 0 ? Double.NaN : s / n;
    }
}
