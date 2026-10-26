package com.aiinvestor.strategy;

import com.aiinvestor.indicators.IndicatorSet;

/** A deterministic, independently testable setup detector. Must be pure: same inputs → same output. */
public interface Strategy {
    String name();
    String version();
    /** Documented defaults; every key is overridable via configuration or per-backtest overrides. */
    Params defaultParams();
    StrategyResult evaluate(CandleContext ctx, IndicatorSet ind, Params params);
}
