package com.aiinvestor.ai;

/** The real analyst: delegates to Claude through {@link ClaudeGateway}. */
public class ClaudeAnalyst implements AiAnalyst {
    private final ClaudeGateway gateway;

    public ClaudeAnalyst(ClaudeGateway gateway) { this.gateway = gateway; }

    @Override public String name() { return "CLAUDE"; }
    @Override public String modelId() { return gateway.modelId(); }
    @Override public boolean isConfigured() { return gateway.isConfigured(); }

    @Override public ClaudeGateway.RawResult complete(AnalysisRequest r, String system, String user) {
        return gateway.complete(system, user);
    }
}
