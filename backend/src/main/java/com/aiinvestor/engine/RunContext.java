package com.aiinvestor.engine;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Enums.AiMode;
import com.aiinvestor.domain.Enums.RunMode;
import com.aiinvestor.strategy.Params;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Everything that defines one trading run. Backtests construct their own contexts (different mode / risk / strategy
 * parameter overrides) while sharing exactly the same engine code as paper trading.
 */
public record RunContext(String runId, RunMode mode, AiMode aiMode, BigDecimal initialCapital, AppProperties.Risk risk,
                         ExecutionAssumptions exec, String dataSource, Map<String, Params> strategyOverrides,
                         boolean syntheticQuotes) {

    public Params overridesFor(String strategy) { return strategyOverrides == null ? null : strategyOverrides.get(strategy); }

    public RunContext withIdAndMode(String newRunId, AiMode newAiMode) {
        return new RunContext(newRunId, mode, newAiMode, initialCapital, risk, exec, dataSource, strategyOverrides, syntheticQuotes);
    }
}
