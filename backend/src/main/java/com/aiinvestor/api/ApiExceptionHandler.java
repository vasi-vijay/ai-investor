package com.aiinvestor.api;

import com.aiinvestor.live.LiveExecutionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Uniform, non-leaking error bodies: no stack traces, no internal class names, no credentials. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) { return body(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage()); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String f = e.getBindingResult().getFieldErrors().stream().map(x -> x.getField() + " " + x.getDefaultMessage()).findFirst().orElse("invalid request");
        return body(HttpStatus.BAD_REQUEST, "validation_failed", f);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) { return body(HttpStatus.CONFLICT, "conflict", e.getMessage()); }

    @ExceptionHandler(LiveExecutionGate.LiveDisabledException.class)
    ResponseEntity<Map<String, String>> liveDisabled(LiveExecutionGate.LiveDisabledException e) { return body(HttpStatus.FORBIDDEN, "live_disabled", e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, String>> denied(AccessDeniedException e) { return body(HttpStatus.FORBIDDEN, "forbidden", "insufficient role"); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> other(Exception e) {
        log.warn("unhandled API error: {}", e.getClass().getSimpleName());
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "unexpected error");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus s, String code, String msg) {
        return ResponseEntity.status(s).body(Map.of("error", code, "message", com.aiinvestor.util.Redactor.redact(msg == null ? "" : msg)));
    }
}
