package com.aiinvestor.ai;

import java.util.List;

/**
 * Result of one analysis attempt. {@code rawResponse} is the untouched model text; {@code assessment} exists only
 * when status == VALID. Any other status means the response must be treated as NO_TRADE.
 */
public record AnalysisOutcome(Status status, AiAssessment assessment, String analyst, String model, String requestJson,
                              String rawResponse, String parsedJson, List<String> errors, Long latencyMs,
                              Long inputTokens, Long outputTokens, String promptHash, String promptVersion,
                              String reusedFromId, String recordId) {
    public enum Status { VALID, INVALID, ERROR, TIMEOUT, REFUSED, SKIPPED }
    public boolean isValid() { return status == Status.VALID && assessment != null; }
}
