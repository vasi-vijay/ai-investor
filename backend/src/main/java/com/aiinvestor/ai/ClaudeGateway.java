package com.aiinvestor.ai;

/** Thin seam over the Anthropic SDK so the analyst logic is testable without network access. */
public interface ClaudeGateway {
    record RawResult(String text, String model, Long inputTokens, Long outputTokens, long latencyMs, String stopReason) {}

    /** @throws AiException (NOT_CONFIGURED, TIMEOUT, REFUSED, API_ERROR) */
    RawResult complete(String systemPrompt, String userPrompt);

    boolean isConfigured();

    String modelId();
}
