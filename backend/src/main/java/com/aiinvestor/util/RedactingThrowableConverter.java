package com.aiinvestor.util;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback converter: %redactedEx (stack traces with credentials scrubbed). */
public class RedactingThrowableConverter extends ThrowableProxyConverter {
    @Override public String convert(ILoggingEvent event) { return Redactor.redact(super.convert(event)); }
}
