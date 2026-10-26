package com.aiinvestor.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DETERMINISTIC STAND-IN for Claude, used when no API key is configured so the whole pipeline (validation, audit,
 * gating, comparison) can be exercised offline. It reads only the snapshot's indicator fields and applies a few
 * fixed filters. Results produced by this analyst say NOTHING about how Claude performs and are labelled
 * MOCK_HEURISTIC everywhere.
 */
public class HeuristicMockAnalyst implements AiAnalyst {
    private final ObjectMapper mapper;

    public HeuristicMockAnalyst(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public String name() { return "MOCK_HEURISTIC"; }
    @Override public String modelId() { return "mock-heuristic-v1"; }
    @Override public boolean isConfigured() { return true; }

    @SuppressWarnings("unchecked")
    @Override public ClaudeGateway.RawResult complete(AnalysisRequest r, String system, String user) {
        Map<String, Object> ind = (Map<String, Object>) r.snapshot().getOrDefault("indicators", Map.of());
        Map<String, Object> prop = (Map<String, Object>) r.snapshot().getOrDefault("strategy_proposal", Map.of());
        boolean buy = "BUY_CANDIDATE".equals(String.valueOf(prop.get("decision")));
        double rsi = num(ind.get("rsi14"), 50), atrPct = num(ind.get("atr_pct"), 0.001);
        double e9 = num(ind.get("ema9"), 0), e21 = num(ind.get("ema21"), 0), atr = num(ind.get("atr14"), 1);
        double gapAtr = atr == 0 ? 0 : Math.abs(e9 - e21) / atr;
        List<String> sup = new ArrayList<>(), con = new ArrayList<>(), miss = new ArrayList<>();
        boolean ok = true;
        if (gapAtr >= 0.15) sup.add("EMA9/EMA21 separation " + String.format("%.2f", gapAtr) + " ATR"); else { ok = false; con.add("EMA separation weak (" + String.format("%.2f", gapAtr) + " ATR)"); }
        if (buy ? rsi <= 75 : rsi >= 25) sup.add("RSI " + String.format("%.1f", rsi) + " not exhausted"); else { ok = false; con.add("RSI " + String.format("%.1f", rsi) + " exhausted"); }
        if (atrPct <= 0.002) sup.add("volatility acceptable"); else { ok = false; con.add("ATR% " + String.format("%.4f", atrPct) + " too high"); }
        if (r.snapshot().get("option_plan") != null) {
            Map<String, Object> op = (Map<String, Object>) r.snapshot().get("option_plan");
            if (num(op.get("spread_pct"), 0) > 0.025) { ok = false; con.add("option spread wide"); }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instrument", r.instrumentLabel());
        out.put("decision", ok ? (buy ? "BUY_CANDIDATE" : "SELL_CANDIDATE") : "NO_TRADE");
        out.put("strategy", r.strategyName());
        out.put("timeframe", r.timeframe());
        out.put("confidence", ok ? 60 : 30);
        out.put("entry_price", ok ? prop.get("entry") : null);
        out.put("stop_loss", ok ? prop.get("stop") : null);
        out.put("target_price", ok ? prop.get("target") : null);
        out.put("supporting_conditions", sup);
        out.put("contradicting_conditions", con);
        out.put("invalidation_conditions", List.of("close back through the strategy invalidation level"));
        miss.add("order-book depth, news and market breadth are not available to this stand-in");
        out.put("missing_information", miss);
        out.put("strategy_agreement", ok ? "AGREES" : "DISAGREES");
        out.put("disagreement_explanation", ok ? "" : "mock heuristic filters failed");
        out.put("reject_trade", !ok);
        out.put("reasoning", List.of("deterministic mock heuristic (not Claude)"));
        out.put("data_quality", "VALID");
        try {
            return new ClaudeGateway.RawResult(mapper.writeValueAsString(out), modelId(), 0L, 0L, 0, "end_turn");
        } catch (JsonProcessingException e) {
            throw new AiException(AiException.Kind.API_ERROR, "mock serialisation failed");
        }
    }

    private static double num(Object o, double def) { return o instanceof Number n ? n.doubleValue() : def; }
}
