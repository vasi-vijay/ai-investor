package com.aiinvestor.ai;

import com.aiinvestor.domain.Enums.Decision;

import java.util.Map;

/** Input to an analyst: a compact validated snapshot + identifiers needed to validate the response. */
public record AnalysisRequest(String runId, String runMode, String signalId, String instrumentLabel, String strategyName,
                              String strategyVersion, String timeframe, Decision rulesDecision, double lastClose,
                              double atr, Map<String, Object> snapshot) {}
