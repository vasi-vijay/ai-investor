package com.aiinvestor.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;
import java.util.Optional;

/**
 * Wire schema sent to Claude as the structured-output JSON schema (schema_version {@link #SCHEMA_VERSION}).
 * It is UNTRUSTED input once returned: {@link AiResponseValidator} converts it to {@link AiAssessment} only after
 * every field passes validation. Prices are optional (null when no trade is proposed).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaudeAssessmentWire(
        @JsonProperty("instrument") @JsonPropertyDescription("Trading symbol exactly as given in the snapshot") String instrument,
        @JsonProperty("decision") @JsonPropertyDescription("BUY_CANDIDATE, SELL_CANDIDATE, NO_TRADE or INSUFFICIENT_DATA") Decision decision,
        @JsonProperty("strategy") @JsonPropertyDescription("Strategy name exactly as given in the snapshot") String strategy,
        @JsonProperty("timeframe") @JsonPropertyDescription("Timeframe exactly as given, e.g. 5m") String timeframe,
        @JsonProperty("confidence") @JsonPropertyDescription("0-100 subjective confidence in the assessment. NOT a probability of profit") int confidence,
        @JsonProperty("entry_price") @JsonPropertyDescription("Proposed entry in instrument price units, or null if no trade") Optional<Double> entryPrice,
        @JsonProperty("stop_loss") @JsonPropertyDescription("Proposed stop-loss, or null if no trade") Optional<Double> stopLoss,
        @JsonProperty("target_price") @JsonPropertyDescription("Proposed target, or null if no trade") Optional<Double> targetPrice,
        @JsonProperty("supporting_conditions") @JsonPropertyDescription("Conditions in the snapshot that support the candidate") List<String> supportingConditions,
        @JsonProperty("contradicting_conditions") @JsonPropertyDescription("Conditions in the snapshot that contradict the candidate") List<String> contradictingConditions,
        @JsonProperty("invalidation_conditions") @JsonPropertyDescription("Observable evidence that would invalidate the setup") List<String> invalidationConditions,
        @JsonProperty("missing_information") @JsonPropertyDescription("Information that was missing or would improve the assessment") List<String> missingInformation,
        @JsonProperty("strategy_agreement") @JsonPropertyDescription("AGREES if your decision matches the strategy's proposed direction, else DISAGREES") Agreement strategyAgreement,
        @JsonProperty("disagreement_explanation") @JsonPropertyDescription("Why you disagree with the strategy; empty string if you agree") String disagreementExplanation,
        @JsonProperty("reject_trade") @JsonPropertyDescription("True if this trade should be rejected") boolean rejectTrade,
        @JsonProperty("reasoning") @JsonPropertyDescription("Short ordered reasoning steps") List<String> reasoning,
        @JsonProperty("data_quality") @JsonPropertyDescription("VALID, STALE, INCOMPLETE or INCONSISTENT, judged from the snapshot") DataQ dataQuality) {

    public static final String SCHEMA_VERSION = "1";

    public enum Decision { BUY_CANDIDATE, SELL_CANDIDATE, NO_TRADE, INSUFFICIENT_DATA }
    public enum Agreement { AGREES, DISAGREES }
    public enum DataQ { VALID, STALE, INCOMPLETE, INCONSISTENT }
}
