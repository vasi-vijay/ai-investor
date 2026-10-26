package com.aiinvestor.ai;

public class AiException extends RuntimeException {
    public enum Kind { NOT_CONFIGURED, TIMEOUT, REFUSED, API_ERROR, BUDGET_EXHAUSTED }
    private final Kind kind;
    public AiException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
    public AiException(Kind kind, String message) { this(kind, message, null); }
    public Kind kind() { return kind; }
}
