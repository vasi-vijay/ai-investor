package com.aiinvestor.mock;

/** Textbook Black-Scholes, used ONLY to build synthetic option prices for the mock provider. */
final class BlackScholes {
    private BlackScholes() {}

    static double price(boolean call, double s, double k, double tYears, double r, double vol) {
        if (tYears <= 0) return Math.max(0, call ? s - k : k - s);
        double sd = vol * Math.sqrt(tYears);
        double d1 = (Math.log(s / k) + (r + 0.5 * vol * vol) * tYears) / sd;
        double d2 = d1 - sd;
        double disc = Math.exp(-r * tYears);
        return call ? s * cdf(d1) - k * disc * cdf(d2) : k * disc * cdf(-d2) - s * cdf(-d1);
    }

    static double cdf(double x) {
        // Abramowitz-Stegun 7.1.26 via erf approximation
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double d = 0.3989423 * Math.exp(-x * x / 2.0);
        double p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))));
        return x > 0 ? 1.0 - p : p;
    }
}
