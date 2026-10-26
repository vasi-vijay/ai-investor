package com.aiinvestor.ai;

import com.aiinvestor.domain.Enums.DataQuality;
import com.aiinvestor.domain.Enums.Decision;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * VALIDATED Claude assessment — the only AI-derived object the rest of the application may use. {@code confidence}
 * is an uncalibrated model output and must never be treated as a probability of profit.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiAssessment(String instrument, Decision decision, String strategy, String timeframe, int confidence,
                           Double entryPrice, Double stopLoss, Double targetPrice,
                           List<String> supporting, List<String> contradicting, List<String> invalidation,
                           List<String> missingInformation, boolean claimsAgreement, String disagreementExplanation,
                           boolean rejectTrade, List<String> reasoning, DataQuality dataQuality) {}
