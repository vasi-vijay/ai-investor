package com.aiinvestor.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** BigDecimal helpers: money in 2dp, prices rounded to the instrument tick. */
public final class Money {
    private Money() {}

    public static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }

    public static BigDecimal money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_EVEN); }

    public static BigDecimal money(double v) { return money(BigDecimal.valueOf(v)); }

    public static BigDecimal price4(BigDecimal v) { return v.setScale(4, RoundingMode.HALF_EVEN); }

    /** Round to nearest tick (ties to even). */
    public static BigDecimal roundToTick(BigDecimal price, BigDecimal tick) {
        if (tick == null || tick.signum() <= 0) return price;
        return price.divide(tick, 0, RoundingMode.HALF_EVEN).multiply(tick).setScale(4, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal floorToTick(BigDecimal price, BigDecimal tick) {
        return price.divide(tick, 0, RoundingMode.FLOOR).multiply(tick).setScale(4, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal ceilToTick(BigDecimal price, BigDecimal tick) {
        return price.divide(tick, 0, RoundingMode.CEILING).multiply(tick).setScale(4, RoundingMode.HALF_EVEN);
    }

    public static double roundToTick(double price, double tick) {
        return tick <= 0 ? price : Math.round(price / tick) * tick;
    }
}
