package com.aiinvestor.support;

import com.aiinvestor.ai.AiAnalyst;
import com.aiinvestor.ai.AiException;
import com.aiinvestor.ai.AnalysisRequest;
import com.aiinvestor.ai.ClaudeGateway;
import com.aiinvestor.domain.Enums.Decision;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Test double standing in for Claude: behaviour is scripted per test; no network. Named CLAUDE so budgets apply. */
public class ScriptedAnalyst implements AiAnalyst {
    public final AtomicInteger calls = new AtomicInteger();
    public volatile boolean configured = true;
    public volatile Function<AnalysisRequest, String> script = ScriptedAnalyst::agree;

    @Override public String name() { return "CLAUDE"; }
    @Override public String modelId() { return "scripted-test-model"; }
    @Override public boolean isConfigured() { return configured; }

    @Override public ClaudeGateway.RawResult complete(AnalysisRequest r, String system, String user) {
        calls.incrementAndGet();
        return new ClaudeGateway.RawResult(script.apply(r), modelId(), 100L, 50L, 12, "end_turn");
    }

    public static String agree(AnalysisRequest r) { return response(r, r.rulesDecision() == Decision.BUY_CANDIDATE ? "BUY_CANDIDATE" : "SELL_CANDIDATE", r.lastClose(), false); }

    public static String disagree(AnalysisRequest r) { return response(r, "NO_TRADE", r.lastClose(), true); }

    @SuppressWarnings("unchecked")
    public static String response(AnalysisRequest r, String decision, double close, boolean reject) {
        Object prop = r.snapshot().get("strategy_proposal");
        java.util.Map<String, Object> p = (java.util.Map<String, Object>) prop;
        boolean cand = decision.endsWith("CANDIDATE");
        return """
                {"instrument":"%s","decision":"%s","strategy":"%s","timeframe":"%s","confidence":70,
                 "entry_price":%s,"stop_loss":%s,"target_price":%s,"supporting_conditions":["scripted"],"contradicting_conditions":[],
                 "invalidation_conditions":["scripted"],"missing_information":[],"strategy_agreement":"%s","disagreement_explanation":"%s",
                 "reject_trade":%s,"reasoning":["scripted test response"],"data_quality":"VALID"}"""
                .formatted(r.instrumentLabel(), decision, r.strategyName(), r.timeframe(), cand ? p.get("entry") : "null", cand ? p.get("stop") : "null",
                        cand ? p.get("target") : "null", reject ? "DISAGREES" : "AGREES", reject ? "scripted disagreement" : "", reject);
    }

    public static AiException timeout() { return new AiException(AiException.Kind.TIMEOUT, "Claude API timed out after 60s"); }
}
