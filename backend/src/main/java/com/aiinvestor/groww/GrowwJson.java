package com.aiinvestor.groww;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/** Tolerant accessors: Groww response field names beyond the request side are partly UNVERIFIED, so try known aliases. */
final class GrowwJson {
    private GrowwJson() {}

    static Double num(JsonNode n, String... names) {
        if (n == null) return null;
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && v.isNumber()) return v.asDouble();
            if (v != null && v.isTextual()) { try { return Double.parseDouble(v.asText()); } catch (NumberFormatException ignored) { } }
        }
        return null;
    }

    static BigDecimal dec(JsonNode n, String... names) {
        Double d = num(n, names);
        return d == null ? null : BigDecimal.valueOf(d);
    }

    static String text(JsonNode n, String... names) {
        if (n == null) return null;
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    static int integer(JsonNode n, int def, String... names) {
        Double d = num(n, names);
        return d == null ? def : d.intValue();
    }
}
