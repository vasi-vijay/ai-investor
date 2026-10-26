package com.aiinvestor.engine;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Enums.OrderType;

/** Simulation assumptions held identical across rules-only / AI-shadow / AI-gated comparisons. */
public record ExecutionAssumptions(double equitySlippageBps, double optionSlippagePct, double maxParticipation,
                                   int entryTtlMinutes, OrderType entryOrderType,
                                   double assumedEquitySpreadBps, double assumedOptionSpreadPct) {

    public static ExecutionAssumptions from(AppProperties.Paper p) {
        return new ExecutionAssumptions(p.equitySlippageBps(), p.optionSlippagePct(), p.maxVolumeParticipation(),
                p.entryOrderTtlMinutes(), OrderType.valueOf(p.entryOrderType().toUpperCase()), 3.0, 1.5);
    }
}
