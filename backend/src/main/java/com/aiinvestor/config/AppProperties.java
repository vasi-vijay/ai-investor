package com.aiinvestor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Typed application configuration (prefix {@code app}). Defaults are deliberately conservative simulation
 * values, NOT trading recommendations. Secrets are never defaults here; they come from environment variables.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue Market market,
        @DefaultValue Data data,
        @DefaultValue Universe universe,
        @DefaultValue Mock mock,
        @DefaultValue Paper paper,
        @DefaultValue Costs costs,
        @DefaultValue Risk risk,
        @DefaultValue Ai ai,
        @DefaultValue Groww groww,
        @DefaultValue Live live,
        @DefaultValue Security security,
        @DefaultValue Scheduler scheduler,
        /** Per-strategy parameter overrides: app.strategies.VWAP_TREND.stopAtrMultiple=1.2 etc. */
        Map<String, Map<String, Object>> strategies) {

    /** Session times are in {@code timezone}. Holidays MUST be maintained by the operator (no hardcoded calendar). */
    public record Market(
            @DefaultValue("Asia/Kolkata") String timezone,
            @DefaultValue("09:15") String open,
            @DefaultValue("15:30") String close,
            @DefaultValue("15:00") String noNewEntriesAfter,
            @DefaultValue("15:15") String squareOffAt,
            List<LocalDate> holidays) {}

    public record Data(
            /** GROWW (real market data; needs credentials + data subscription) or MOCK (synthetic; must be selected explicitly, never a fallback). */
            @DefaultValue("GROWW") String source,
            @DefaultValue("5") int timeframeMinutes,
            @DefaultValue("30") int maxQuoteAgeSeconds,
            @DefaultValue("3") int warmupDays,
            @DefaultValue("30") int minBarsRequired,
            @DefaultValue("3") int maxMissingBars) {}

    public record Universe(
            @DefaultValue({"RELIANCE", "TCS", "INFY", "HDFCBANK"}) List<String> equities,
            @DefaultValue({"NIFTY", "BANKNIFTY"}) List<String> indexUnderlyings,
            @DefaultValue("true") boolean optionsEnabled) {}

    /** Settings for the synthetic provider only. Every value here is fabricated test data. */
    public record Mock(
            /** Step through a simulated trading day so the pipeline can run outside market hours. */
            @DefaultValue("true") boolean replayEnabled,
            LocalDate replayStartDate,
            @DefaultValue("3") int replayStepSeconds,
            Map<String, Double> basePrices,
            Map<String, Integer> lotSizes) {}

    public record Paper(
            @DefaultValue("1000000") BigDecimal initialCapital,
            @DefaultValue("5") double equitySlippageBps,
            @DefaultValue("0.5") double optionSlippagePct,
            /** Max fraction of a bar's volume one simulated order may consume per bar (drives partial fills). */
            @DefaultValue("0.10") double maxVolumeParticipation,
            @DefaultValue("5") int entryOrderTtlMinutes,
            @DefaultValue("MARKET") String entryOrderType,
            @DefaultValue("paper-default") String runName,
            /** RULES_ONLY | AI_SHADOW | AI_GATED. Changing it starts a NEW paper run so P&L is never mixed. */
            @DefaultValue("AI_SHADOW") String aiMode) {}

    /**
     * Cost model parameters. Defaults are APPROXIMATE illustrative values for NSE intraday and MUST be verified
     * against the current Groww / NSE / SEBI / government schedules before relying on net P&L figures.
     */
    public record Costs(
            @DefaultValue("20") BigDecimal brokerageFlatPerOrder,
            @DefaultValue("0.001") double brokerageMaxPct,
            @DefaultValue("0.00025") double sttEquitySellPct,
            @DefaultValue("0.001") double sttOptionSellPct,
            @DefaultValue("0.0000297") double exchangeTxnEquityPct,
            @DefaultValue("0.0003503") double exchangeTxnOptionPct,
            @DefaultValue("0.000001") double sebiPct,
            @DefaultValue("0.00003") double stampBuyPct,
            @DefaultValue("0.18") double gstPct) {}

    public record Risk(
            @DefaultValue Global global,
            @DefaultValue EquityRisk equity,
            @DefaultValue OptionRisk options) {}

    public record Global(
            @DefaultValue("0.50") double maxCapitalAllocationPct,
            @DefaultValue("15000") BigDecimal maxDailyLoss,
            @DefaultValue("10") int maxTradesPerDay,
            @DefaultValue("3") int maxOpenPositions,
            @DefaultValue("0.20") double maxExposurePerInstrumentPct,
            @DefaultValue("1.5") double minRiskReward,
            @DefaultValue("3") int maxConsecutiveLosses,
            @DefaultValue("30") int maxQuoteAgeSeconds) {}

    public record EquityRisk(
            @DefaultValue("2000") BigDecimal maxRiskPerTrade,
            @DefaultValue("15") double maxSpreadBps,
            @DefaultValue("20000") long minAvgBarVolume,
            @DefaultValue("0.05") double maxQtyFractionOfAvgVolume,
            @DefaultValue("3") int minStopTicks,
            @DefaultValue("true") boolean allowShort,
            @DefaultValue("0.30") double maxCostToRewardRatio) {}

    public record OptionRisk(
            @DefaultValue("2000") BigDecimal maxLossPerTrade,
            @DefaultValue("25000") BigDecimal maxPremiumPerTrade,
            @DefaultValue("0.03") double maxSpreadPct,
            @DefaultValue("10") double minPremium,
            @DefaultValue("800") double maxPremium,
            @DefaultValue("500") long minVolume,
            @DefaultValue("0") long minOpenInterest,
            @DefaultValue("1") int minDaysToExpiry,
            @DefaultValue("0.25") double stopLossPct,
            @DefaultValue("1.5") double rewardMultiple,
            @DefaultValue("5") int maxLots,
            @DefaultValue("0.35") double maxPremiumMoveFraction,
            @DefaultValue("3") int premiumMoveLookbackBars,
            @DefaultValue("1") int strikeStepsFromAtm,
            @DefaultValue("0.4") double maxCostToRewardRatio) {}

    public record Ai(
            @DefaultValue("true") boolean enabled,
            /** CLAUDE (Anthropic API) or MOCK_HEURISTIC (deterministic stand-in; NOT evidence about Claude). */
            @DefaultValue("CLAUDE") String provider,
            @DefaultValue("claude-opus-5-5") String model,
            @DefaultValue("60") int timeoutSeconds,
            @DefaultValue("4000") int maxOutputTokens,
            @DefaultValue("medium") String effort,
            @DefaultValue("200") int maxCallsPerDay,
            @DefaultValue("500") int maxCallsPerBacktest,
            @DefaultValue("40") int maxCandlesInPrompt,
            @DefaultValue("p1") String promptVersion,
            @DefaultValue("true") boolean reuseIdenticalPrompts) {}

    public record Groww(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("https://api.groww.in/v1") String baseUrl,
            @DefaultValue("https://growwapi-assets.groww.in/instruments/instrument.csv") String instrumentCsvUrl,
            @DefaultValue("10") int requestTimeoutSeconds,
            @DefaultValue("5") int maxRequestsPerSecond,
            /** Auth inputs come from env: GROWW_ACCESS_TOKEN, or GROWW_API_KEY + (GROWW_API_SECRET | GROWW_TOTP_SECRET). */
            String accessToken,
            /** Optional file (e.g. Docker/K8s secret) re-read on expiry so rotated tokens are picked up without a restart. */
            String accessTokenFile,
            String apiKey,
            String apiSecret,
            String totpSecret) {}

    public record Live(
            @DefaultValue("false") boolean enabled,
            /** Operator attestation that regulatory/exchange/Groww requirements were verified. Default: not confirmed. */
            @DefaultValue("false") boolean complianceConfirmed,
            @DefaultValue("true") boolean requireManualApproval,
            @DefaultValue("30") int approvalTtlMinutes,
            @DefaultValue("50000") BigDecimal maxOrderValue) {}

    public record Security(
            @DefaultValue("admin") String adminUser,
            String adminPasswordBcrypt,
            String adminPassword,
            String viewerUser,
            String viewerPasswordBcrypt,
            @DefaultValue("120") int apiRequestsPerMinute,
            @DefaultValue("http://localhost:5173") List<String> allowedOrigins) {}

    public record Scheduler(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20") int pollSeconds) {}
}
