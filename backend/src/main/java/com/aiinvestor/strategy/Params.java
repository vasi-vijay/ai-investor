package com.aiinvestor.strategy;

import java.util.LinkedHashMap;
import java.util.Map;

/** Strategy parameter bag: JSON-friendly, overridable per run/backtest, with typed defaulting accessors. */
public record Params(Map<String, Object> values) {
    public Params { values = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values); }

    public static Params of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return new Params(m);
    }

    public double d(String key, double def) {
        Object v = values.get(key);
        return v instanceof Number n ? n.doubleValue() : v instanceof String s ? Double.parseDouble(s) : def;
    }

    public int i(String key, int def) {
        Object v = values.get(key);
        return v instanceof Number n ? n.intValue() : v instanceof String s ? Integer.parseInt(s) : def;
    }

    public boolean b(String key, boolean def) {
        Object v = values.get(key);
        return v instanceof Boolean b ? b : v instanceof String s ? Boolean.parseBoolean(s) : def;
    }

    public String s(String key, String def) {
        Object v = values.get(key);
        return v == null ? def : v.toString();
    }

    /** Defaults overlaid with overrides (overrides win). */
    public Params merge(Params overrides) {
        Map<String, Object> m = new LinkedHashMap<>(values);
        if (overrides != null) m.putAll(overrides.values);
        return new Params(m);
    }
}
