package com.aiinvestor.domain;

/** Candle interval in minutes with the "5m" style label used in prompts and persistence. */
public record TimeFrame(int minutes) {
    public TimeFrame {
        if (minutes <= 0 || minutes > 240) throw new IllegalArgumentException("unsupported timeframe: " + minutes);
    }
    public static TimeFrame parse(String label) {
        String s = label.trim().toLowerCase();
        if (s.endsWith("m")) return new TimeFrame(Integer.parseInt(s.substring(0, s.length() - 1)));
        throw new IllegalArgumentException("unsupported timeframe label: " + label);
    }
    public String label() { return minutes + "m"; }
    @Override public String toString() { return label(); }
}
