package com.aiinvestor.domain;

/** Small enums shared across modules. Kept in one file to make the vocabulary easy to scan. */
public final class Enums {
    private Enums() {}

    public enum InstrumentType { EQUITY, INDEX, OPTION }
    public enum OptionType { CE, PE }
    public enum Side { BUY, SELL; public Side opposite() { return this == BUY ? SELL : BUY; } }
    public enum Direction { LONG, SHORT }
    /** Separates the three P&L universes. Statistics are never mixed across modes. */
    public enum RunMode { BACKTEST, PAPER, LIVE }
    /** RULES_ONLY = deterministic only; AI_SHADOW = Claude evaluates but does not gate; AI_GATED = Claude must agree. */
    public enum AiMode { RULES_ONLY, AI_SHADOW, AI_GATED }
    public enum Decision { BUY_CANDIDATE, SELL_CANDIDATE, NO_TRADE, INSUFFICIENT_DATA }
    public enum OrderType { MARKET, LIMIT }
    public enum OrderStatus { PENDING, PARTIAL, FILLED, CANCELLED, REJECTED }
    public enum OrderPurpose { ENTRY, STOP_LOSS, TARGET, SQUARE_OFF }
    public enum FinalStatus { NO_TRADE, REJECTED, APPROVED, ERROR }
    public enum DataQuality { VALID, STALE, INCOMPLETE, INCONSISTENT, MISSING }
}
