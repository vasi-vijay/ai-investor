package com.aiinvestor.ai;

/** Produces raw assessment text for a request. Implementations: Claude (SDK) and a deterministic mock heuristic. */
public interface AiAnalyst {
    /** Stored in ai_analysis_records.analyst: CLAUDE or MOCK_HEURISTIC. */
    String name();

    String modelId();

    boolean isConfigured();

    /** @throws AiException on timeout / refusal / API failure. */
    ClaudeGateway.RawResult complete(AnalysisRequest request, String systemPrompt, String userPrompt);
}
