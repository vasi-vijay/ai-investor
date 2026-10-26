package com.aiinvestor.broker;

/** Base for broker failures. Messages must never contain tokens or secrets. */
public class BrokerException extends RuntimeException {
    public BrokerException(String message) { super(message); }
    public BrokerException(String message, Throwable cause) { super(message, cause); }

    /** 401: token expired/invalid. Broker-dependent work must pause until re-authorised. */
    public static class Auth extends BrokerException {
        public Auth(String m) { super(m); }
    }
    /** 403: authenticated but not entitled (e.g. missing data subscription). */
    public static class NotEntitled extends BrokerException {
        public NotEntitled(String m) { super(m); }
    }
    public static class RateLimited extends BrokerException {
        public RateLimited(String m) { super(m); }
    }
    /** Definitive rejection by the broker (400). Safe to treat as "no order exists". */
    public static class Rejected extends BrokerException {
        public Rejected(String m) { super(m); }
    }
    /**
     * Outcome unknown (timeout / 5xx / connection drop after send). The order MAY exist at the broker.
     * Callers must reconcile by reference before any retry; automatic resubmission is forbidden.
     */
    public static class Ambiguous extends BrokerException {
        public Ambiguous(String m, Throwable cause) { super(m, cause); }
    }
    public static class Unavailable extends BrokerException {
        public Unavailable(String m) { super(m); }
    }
}
