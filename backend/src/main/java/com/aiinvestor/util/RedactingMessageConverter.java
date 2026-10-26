package com.aiinvestor.util;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback converter: %redactedMsg */
public class RedactingMessageConverter extends ClassicConverter {
    @Override public String convert(ILoggingEvent event) { return Redactor.redact(event.getFormattedMessage()); }
}
