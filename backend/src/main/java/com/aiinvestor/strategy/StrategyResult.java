package com.aiinvestor.strategy;

import com.aiinvestor.domain.Enums.Decision;

import java.util.List;

/**
 * Deterministic strategy output. For NO_TRADE / INSUFFICIENT_DATA the price fields are null and
 * {@code contradicting} explains why. Prices are in the units of the analysed (underlying) instrument.
 */
public record StrategyResult(Decision decision, Double entry, Double stop, Double target,
                             List<String> supporting, List<String> contradicting, List<String> invalidation) {

    public boolean isCandidate() { return decision == Decision.BUY_CANDIDATE || decision == Decision.SELL_CANDIDATE; }

    public double riskReward() {
        if (!isCandidate()) return Double.NaN;
        double risk = Math.abs(entry - stop);
        return risk == 0 ? Double.NaN : Math.abs(target - entry) / risk;
    }

    public static StrategyResult noTrade(List<String> supporting, List<String> contradicting) {
        return new StrategyResult(Decision.NO_TRADE, null, null, null, supporting, contradicting, List.of());
    }

    public static StrategyResult noTrade(String reason) {
        return noTrade(List.of(), List.of(reason));
    }

    public static StrategyResult insufficient(String reason) {
        return new StrategyResult(Decision.INSUFFICIENT_DATA, null, null, null, List.of(), List.of(reason), List.of());
    }
}
