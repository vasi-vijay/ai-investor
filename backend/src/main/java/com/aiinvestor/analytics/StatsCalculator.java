package com.aiinvestor.analytics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure performance statistics over a chronological list of per-trade net P&L (INR). */
public final class StatsCalculator {
    private StatsCalculator() {}

    public static final int MIN_SAMPLE_FOR_CONCLUSIONS = 30;

    public static Map<String, Object> compute(List<Double> pnl, double initialCapital) {
        Map<String, Object> m = new LinkedHashMap<>();
        int n = pnl.size();
        double net = 0, wins = 0, losses = 0;
        int w = 0, l = 0;
        double peak = 0, equity = 0, maxDd = 0;
        for (double p : pnl) {
            net += p;
            if (p > 0) { wins += p; w++; } else if (p < 0) { losses += -p; l++; }
            equity += p;
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, peak - equity);
        }
        m.put("trade_count", n);
        m.put("net_pnl", round(net));
        m.put("return_on_capital_pct", initialCapital > 0 ? round(net / initialCapital * 100) : null);
        m.put("win_count", w);
        m.put("loss_count", l);
        m.put("win_rate", n == 0 ? null : round4((double) w / n));
        m.put("profit_factor", losses == 0 ? null : round4(wins / losses));
        m.put("profit_factor_note", losses == 0 ? (wins == 0 ? "no winning or losing trades" : "no losing trades: profit factor undefined") : null);
        m.put("avg_trade", n == 0 ? null : round(net / n));
        m.put("avg_win", w == 0 ? null : round(wins / w));
        m.put("avg_loss", l == 0 ? null : round(-losses / l));
        m.put("gross_wins", round(wins));
        m.put("gross_losses", round(-losses));
        m.put("max_drawdown", round(maxDd));
        m.put("max_drawdown_pct_of_capital", initialCapital > 0 ? round(maxDd / initialCapital * 100) : null);
        if (n > 0) {
            double[] ci = wilson(w, n);
            m.put("win_rate_ci95", List.of(round4(ci[0]), round4(ci[1])));
        }
        if (n > 1) {
            double mean = net / n, ss = 0;
            for (double p : pnl) ss += (p - mean) * (p - mean);
            double sd = Math.sqrt(ss / (n - 1));
            double half = 1.96 * sd / Math.sqrt(n);
            m.put("avg_trade_ci95", List.of(round(mean - half), round(mean + half)));
            m.put("pnl_std_dev", round(sd));
        }
        boolean enough = n >= MIN_SAMPLE_FOR_CONCLUSIONS;
        m.put("sample_size_sufficient", enough);
        if (!enough) m.put("sample_size_warning", "only " + n + " trades (< " + MIN_SAMPLE_FOR_CONCLUSIONS + "): differences are statistically unreliable and must not be read as evidence of edge");
        return m;
    }

    /** Wilson score interval for a proportion (95%). */
    public static double[] wilson(int successes, int n) {
        if (n == 0) return new double[]{0, 0};
        double z = 1.96, p = (double) successes / n;
        double denom = 1 + z * z / n;
        double centre = (p + z * z / (2.0 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom;
        return new double[]{Math.max(0, centre - half), Math.min(1, centre + half)};
    }

    public static double round(double v) { return Math.round(v * 100d) / 100d; }
    public static double round4(double v) { return Math.round(v * 10000d) / 10000d; }
}
