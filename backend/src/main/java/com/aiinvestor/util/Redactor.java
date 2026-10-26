package com.aiinvestor.util;

import java.util.regex.Pattern;

/** Removes credentials from any text that may reach logs, exceptions or API responses. */
public final class Redactor {
    private Redactor() {}

    private static final Pattern[] PATTERNS = {
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=\\-]+"),
            Pattern.compile("(?i)(authorization|api[-_ ]?key|x-api-key|secret|token|password|passwd|totp|otp|checksum)(\"?\\s*[=:]\\s*\"?)([^\\s\",;&}]+)"),
            Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{8,}"),
            Pattern.compile("eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]*")
    };

    public static String redact(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = PATTERNS[0].matcher(s).replaceAll("Bearer [REDACTED]");
        out = PATTERNS[1].matcher(out).replaceAll("$1$2[REDACTED]");
        out = PATTERNS[2].matcher(out).replaceAll("[REDACTED]");
        out = PATTERNS[3].matcher(out).replaceAll("[REDACTED]");
        return out;
    }
}
