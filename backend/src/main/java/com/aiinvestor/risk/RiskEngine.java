package com.aiinvestor.risk;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Enums.Direction;
import com.aiinvestor.domain.Enums.InstrumentType;
import com.aiinvestor.domain.Enums.OptionType;
import com.aiinvestor.domain.Enums.Side;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.engine.RunContext;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.paper.CostModel;
import com.aiinvestor.persistence.DailyStateRepository;
import com.aiinvestor.persistence.DailyStateRepository.DayState;
import com.aiinvestor.persistence.TradeRepository;
import com.aiinvestor.persistence.TradeRepository.OrderRec;
import com.aiinvestor.persistence.TradeRepository.PositionRec;
import com.aiinvestor.risk.RiskModels.CheckResult;
import com.aiinvestor.risk.RiskModels.RiskDecision;
import com.aiinvestor.risk.RiskModels.RiskRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic, independent risk engine. It is the ONLY component that decides order size and approval; AI output
 * can never raise a limit or bypass a check. Every check is evaluated and reported (pass or fail with exact reason).
 * Equity and long-option orders use separate sizing models.
 */
@Component
public class RiskEngine {
    private final KillSwitch killSwitch;
    private final DailyStateRepository daily;
    private final TradeRepository trades;
    private final CostModel costs;
    private final MarketSession session;

    public RiskEngine(KillSwitch killSwitch, DailyStateRepository daily, TradeRepository trades, CostModel costs, MarketSession session) {
        this.killSwitch = killSwitch;
        this.daily = daily;
        this.trades = trades;
        this.costs = costs;
        this.session = session;
    }

    public RiskDecision evaluate(RunContext run, RiskRequest req) {
        List<CheckResult> checks = new ArrayList<>();
        AppProperties.Risk risk = run.risk();
        AppProperties.Global g = risk.global();
        Instrument ins = req.instrument();
        LocalDate day = session.dateOf(req.now());
        DayState ds = daily.get(run.runId(), day);

        BigDecimal capital = run.initialCapital().add(trades.realizedNetTotal(run.runId()));
        List<PositionRec> open = trades.openPositions(run.runId());
        List<OrderRec> pending = trades.openOrders(run.runId());

        boolean killOn = run.mode() != com.aiinvestor.domain.Enums.RunMode.BACKTEST && killSwitch.isEngaged();
        add(checks, "KILL_SWITCH", !killOn, killOn ? "emergency kill switch is engaged" : "off (not applied to historical backtests)");
        add(checks, "DAILY_LOCK", !ds.locked(), ds.locked() ? "trading locked for " + day + ": " + ds.lockReason() : "not locked");
        add(checks, "MARKET_SESSION", session.allowsNewEntries(req.now()), session.allowsNewEntries(req.now())
                ? "session open for new entries" : "outside session or past new-entry cutoff (" + session.statusLabel(req.now()) + ")");

        // data freshness
        if (req.quote() == null) {
            add(checks, "DATA_FRESHNESS", false, "no market quote available");
        } else {
            long age = Math.max(0, Duration.between(req.quote().timestamp(), req.now()).getSeconds());
            int maxAge = g.maxQuoteAgeSeconds();
            add(checks, "DATA_FRESHNESS", age <= maxAge, "quote age " + age + "s (max " + maxAge + "s)" + (req.quoteSynthetic() ? " [synthetic quote from candle]" : ""));
        }

        // price sanity
        boolean pricesOk = req.entry().signum() > 0 && req.stop().signum() > 0 && req.target().signum() > 0;
        boolean longSide = req.direction() == Direction.LONG;
        boolean ordered = longSide ? (req.stop().compareTo(req.entry()) < 0 && req.entry().compareTo(req.target()) < 0)
                                   : (req.target().compareTo(req.entry()) < 0 && req.entry().compareTo(req.stop()) < 0);
        add(checks, "PRICE_LEVELS", pricesOk && ordered, pricesOk && ordered ? "stop/entry/target ordered correctly"
                : "invalid levels entry=" + req.entry() + " stop=" + req.stop() + " target=" + req.target());

        // portfolio limits
        add(checks, "MAX_TRADES_PER_DAY", ds.trades() + countOpenedToday(open, day) < g.maxTradesPerDay(),
                "trades today " + (ds.trades() + countOpenedToday(open, day)) + " (max " + g.maxTradesPerDay() + ")");
        add(checks, "MAX_OPEN_POSITIONS", open.size() + pendingEntries(pending) < g.maxOpenPositions(),
                "open " + open.size() + " + pending " + pendingEntries(pending) + " (max " + g.maxOpenPositions() + ")");
        add(checks, "CONSECUTIVE_LOSSES", ds.consecutiveLosses() < g.maxConsecutiveLosses(),
                "consecutive losses today " + ds.consecutiveLosses() + " (max " + g.maxConsecutiveLosses() + ")");
        boolean dup = open.stream().anyMatch(p -> p.instrumentId() == ins.id())
                || pending.stream().anyMatch(o -> o.instrumentId() == ins.id());
        add(checks, "DUPLICATE_ORDER", !dup, dup ? "open position or pending order already exists for " + ins.tradingSymbol() : "none");

        BigDecimal dailyRoom = g.maxDailyLoss().add(ds.realizedNet()).max(BigDecimal.ZERO);
        add(checks, "DAILY_LOSS_LIMIT", ds.realizedNet().add(g.maxDailyLoss()).signum() > 0,
                "realized P&L today " + ds.realizedNet() + " vs limit -" + g.maxDailyLoss());

        // risk/reward
        BigDecimal stopDist = req.entry().subtract(req.stop()).abs();
        BigDecimal reward = req.target().subtract(req.entry()).abs();
        double rr = stopDist.signum() == 0 ? 0 : reward.divide(stopDist, 6, RoundingMode.HALF_EVEN).doubleValue();
        add(checks, "RISK_REWARD", rr >= g.minRiskReward(), String.format(Locale.ROOT, "R:R %.2f (min %.2f)", rr, g.minRiskReward()));

        // model-specific sizing
        int qty = 0;
        BigDecimal riskAmount = BigDecimal.ZERO;
        if (ins.type() == InstrumentType.OPTION) {
            qty = sizeOption(run, req, checks, dailyRoom, capital);
        } else if (ins.type() == InstrumentType.EQUITY) {
            qty = sizeEquity(run, req, checks, dailyRoom, capital);
        } else {
            add(checks, "INSTRUMENT_TRADABLE", false, ins.type() + " " + ins.tradingSymbol() + " is not directly tradable");
        }
        BigDecimal notional = req.entry().multiply(BigDecimal.valueOf(qty));
        if (qty > 0) {
            riskAmount = stopDist.multiply(BigDecimal.valueOf(qty));
            // exposure limits (on the final quantity)
            BigDecimal perInstrumentCap = capital.multiply(BigDecimal.valueOf(g.maxExposurePerInstrumentPct()));
            add(checks, "EXPOSURE_PER_INSTRUMENT", notional.compareTo(perInstrumentCap) <= 0,
                    "notional " + notional.setScale(0, RoundingMode.HALF_EVEN) + " vs cap " + perInstrumentCap.setScale(0, RoundingMode.HALF_EVEN));
            BigDecimal openNotional = open.stream().map(p -> p.avgEntry().multiply(BigDecimal.valueOf(p.openQuantity()))).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal allocCap = capital.multiply(BigDecimal.valueOf(g.maxCapitalAllocationPct()));
            add(checks, "CAPITAL_ALLOCATION", openNotional.add(notional).compareTo(allocCap) <= 0,
                    "open+new notional " + openNotional.add(notional).setScale(0, RoundingMode.HALF_EVEN) + " vs cap " + allocCap.setScale(0, RoundingMode.HALF_EVEN));
        }
        boolean approved = checks.stream().allMatch(CheckResult::passed) && qty > 0;
        return new RiskDecision(approved, approved ? qty : 0, riskAmount, notional, List.copyOf(checks));
    }

    // ---------------- equity model ----------------

    private int sizeEquity(RunContext run, RiskRequest req, List<CheckResult> checks, BigDecimal dailyRoom, BigDecimal capital) {
        AppProperties.EquityRisk e = run.risk().equity();
        AppProperties.Global g = run.risk().global();
        Instrument ins = req.instrument();
        boolean isShort = req.direction() == Direction.SHORT;
        add(checks, "EQUITY_SHORT_ALLOWED", !isShort || e.allowShort(), isShort ? (e.allowShort() ? "intraday short allowed" : "short selling disabled") : "long");
        add(checks, "EQUITY_OPENING_SIDE", req.openingSide() == (isShort ? Side.SELL : Side.BUY), "opening side " + req.openingSide());

        BigDecimal stopDist = req.entry().subtract(req.stop()).abs();
        BigDecimal minStop = ins.tickSize().multiply(BigDecimal.valueOf(e.minStopTicks()));
        add(checks, "STOP_DISTANCE", stopDist.compareTo(minStop) >= 0, "stop distance " + stopDist + " (min " + minStop + " = " + e.minStopTicks() + " ticks)");

        // spread
        double spreadBps;
        String spreadNote;
        Quote q = req.quote();
        if (q != null && q.hasBook()) { spreadBps = q.spreadFraction() * 10_000; spreadNote = "observed"; }
        else { spreadBps = run.exec().assumedEquitySpreadBps(); spreadNote = "ASSUMED (no two-sided book)"; }
        add(checks, "SPREAD", spreadBps <= e.maxSpreadBps(), String.format(Locale.ROOT, "spread %.1f bps %s (max %.1f)", spreadBps, spreadNote, e.maxSpreadBps()));

        // liquidity
        boolean liq = req.avgBarVolume() >= e.minAvgBarVolume();
        add(checks, "LIQUIDITY", liq, String.format(Locale.ROOT, "avg bar volume %.0f (min %d)", req.avgBarVolume(), e.minAvgBarVolume()));

        if (stopDist.signum() == 0) { add(checks, "SIZING", false, "zero stop distance"); return 0; }
        BigDecimal budget = e.maxRiskPerTrade().min(dailyRoom);
        int byRisk = budget.divide(stopDist, 0, RoundingMode.FLOOR).intValue();
        BigDecimal perInstrument = capital.multiply(BigDecimal.valueOf(g.maxExposurePerInstrumentPct()));
        int byExposure = perInstrument.divide(req.entry(), 0, RoundingMode.FLOOR).intValue();
        BigDecimal openNotional = trades.openPositions(run.runId()).stream()
                .map(p -> p.avgEntry().multiply(BigDecimal.valueOf(p.openQuantity()))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal room = capital.multiply(BigDecimal.valueOf(g.maxCapitalAllocationPct())).subtract(openNotional).max(BigDecimal.ZERO);
        int byAlloc = room.divide(req.entry(), 0, RoundingMode.FLOOR).intValue();
        int byLiq = (int) Math.floor(e.maxQtyFractionOfAvgVolume() * req.avgBarVolume());
        int qty = Math.min(Math.min(byRisk, byExposure), Math.min(byAlloc, byLiq));
        add(checks, "SIZING", qty >= 1, String.format(Locale.ROOT,
                "qty %d = min(risk %d [budget %s / stop %s], exposure %d, allocation %d, liquidity %d)",
                qty, byRisk, budget.setScale(0, RoundingMode.HALF_EVEN), stopDist, byExposure, byAlloc, byLiq));
        if (qty < 1) return 0;

        // cost vs reward
        BigDecimal rewardTotal = req.target().subtract(req.entry()).abs().multiply(BigDecimal.valueOf(qty));
        BigDecimal cost = costs.roundTripEstimate(InstrumentType.EQUITY, req.entry(), qty);
        double ratio = rewardTotal.signum() == 0 ? 1 : cost.divide(rewardTotal, 6, RoundingMode.HALF_EVEN).doubleValue();
        add(checks, "COST_VS_REWARD", ratio <= e.maxCostToRewardRatio(),
                String.format(Locale.ROOT, "est. round-trip cost %s = %.0f%% of target reward (max %.0f%%)", cost.setScale(2, RoundingMode.HALF_EVEN), ratio * 100, e.maxCostToRewardRatio() * 100));
        return qty;
    }

    // ---------------- long-option model ----------------

    private int sizeOption(RunContext run, RiskRequest req, List<CheckResult> checks, BigDecimal dailyRoom, BigDecimal capital) {
        AppProperties.OptionRisk o = run.risk().options();
        Instrument ins = req.instrument();
        LocalDate today = session.dateOf(req.now());

        // initial pilot: long options only
        boolean longBuy = req.direction() == Direction.LONG && req.openingSide() == Side.BUY;
        add(checks, "SHORT_OPTIONS_DISABLED", longBuy, longBuy ? "long option purchase" : "short option / sell-to-open is disabled in the pilot");
        if (!longBuy) return 0;

        // contract validity: must come from authoritative metadata
        boolean valid = ins.expiry() != null && ins.strike() != null && ins.strike().signum() > 0 && ins.optionType() != null
                && ins.lotSize() > 0 && ins.tickSize() != null && ins.tickSize().signum() > 0 && ins.underlying() != null;
        add(checks, "CONTRACT_VALID", valid, valid ? ins.tradingSymbol() + " " + ins.optionType() + " strike " + ins.strike() + " exp " + ins.expiry() + " lot " + ins.lotSize()
                : "incomplete option contract metadata (expiry/strike/type/lot/tick/underlying)");
        if (!valid) return 0;
        add(checks, "CONTRACT_NOT_EXPIRED", !ins.expiry().isBefore(today), "expiry " + ins.expiry() + " vs today " + today);
        long dte = java.time.temporal.ChronoUnit.DAYS.between(today, ins.expiry());
        add(checks, "DAYS_TO_EXPIRY", dte >= o.minDaysToExpiry(), "days to expiry " + dte + " (min " + o.minDaysToExpiry() + "); expiry-day theta/gamma risk avoided");
        boolean tickAligned = req.entry().remainder(ins.tickSize()).signum() == 0;
        add(checks, "TICK_SIZE", tickAligned, "entry " + req.entry() + " vs tick " + ins.tickSize());

        double premium = req.entry().doubleValue();
        add(checks, "PREMIUM_RANGE", premium >= o.minPremium() && premium <= o.maxPremium(),
                String.format(Locale.ROOT, "premium %.2f (allowed %.2f–%.2f)", premium, o.minPremium(), o.maxPremium()));

        Quote q = req.quote();
        double spreadPct;
        String note;
        if (q != null && q.hasBook()) { spreadPct = q.spreadFraction(); note = "observed"; }
        else { spreadPct = run.exec().assumedOptionSpreadPct() / 100.0; note = "ASSUMED (no two-sided book)"; }
        add(checks, "SPREAD", spreadPct <= o.maxSpreadPct(), String.format(Locale.ROOT, "spread %.2f%% %s (max %.2f%%)", spreadPct * 100, note, o.maxSpreadPct() * 100));

        long vol = q == null ? 0 : q.volume();
        add(checks, "LIQUIDITY_VOLUME", vol >= o.minVolume(), "day volume " + vol + " (min " + o.minVolume() + ")");
        if (o.minOpenInterest() > 0) {
            Long oi = q == null ? null : q.openInterest();
            add(checks, "LIQUIDITY_OPEN_INTEREST", oi != null && oi >= o.minOpenInterest(), "open interest " + oi + " (min " + o.minOpenInterest() + ")");
        }
        add(checks, "RAPID_PREMIUM_MOVE", req.recentPremiumMoveFraction() <= o.maxPremiumMoveFraction(),
                String.format(Locale.ROOT, "premium moved %.0f%% over last %d bars (max %.0f%%)", req.recentPremiumMoveFraction() * 100,
                        o.premiumMoveLookbackBars(), o.maxPremiumMoveFraction() * 100));

        BigDecimal perUnitRisk = req.entry().subtract(req.stop()).abs();
        if (perUnitRisk.signum() == 0) { add(checks, "SIZING", false, "zero stop distance"); return 0; }
        BigDecimal lot = BigDecimal.valueOf(ins.lotSize());
        BigDecimal perLotRisk = perUnitRisk.multiply(lot);
        BigDecimal perLotPremium = req.entry().multiply(lot);
        BigDecimal lossBudget = o.maxLossPerTrade().min(dailyRoom);
        int byLoss = lossBudget.divide(perLotRisk, 0, RoundingMode.FLOOR).intValue();
        int byPremium = o.maxPremiumPerTrade().divide(perLotPremium, 0, RoundingMode.FLOOR).intValue();
        BigDecimal openNotional = trades.openPositions(run.runId()).stream()
                .map(p -> p.avgEntry().multiply(BigDecimal.valueOf(p.openQuantity()))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal room = capital.multiply(BigDecimal.valueOf(run.risk().global().maxCapitalAllocationPct())).subtract(openNotional).max(BigDecimal.ZERO);
        int byAlloc = room.divide(perLotPremium, 0, RoundingMode.FLOOR).intValue();
        int lots = Math.min(Math.min(byLoss, byPremium), Math.min(byAlloc, o.maxLots()));
        add(checks, "SIZING", lots >= 1, String.format(Locale.ROOT,
                "lots %d = min(loss-budget %d [%s / %s per lot], premium-cap %d [%s / %s per lot], allocation %d, max-lots %d); total premium at risk %s",
                lots, byLoss, lossBudget.setScale(0, RoundingMode.HALF_EVEN), perLotRisk.setScale(0, RoundingMode.HALF_EVEN),
                byPremium, o.maxPremiumPerTrade().setScale(0, RoundingMode.HALF_EVEN), perLotPremium.setScale(0, RoundingMode.HALF_EVEN),
                byAlloc, o.maxLots(), perLotPremium.multiply(BigDecimal.valueOf(Math.max(lots, 0))).setScale(0, RoundingMode.HALF_EVEN)));
        if (lots < 1) return 0;
        int qty = lots * ins.lotSize();

        BigDecimal rewardTotal = req.target().subtract(req.entry()).abs().multiply(BigDecimal.valueOf(qty));
        BigDecimal cost = costs.roundTripEstimate(InstrumentType.OPTION, req.entry(), qty);
        double ratio = rewardTotal.signum() == 0 ? 1 : cost.divide(rewardTotal, 6, RoundingMode.HALF_EVEN).doubleValue();
        add(checks, "COST_VS_REWARD", ratio <= o.maxCostToRewardRatio(),
                String.format(Locale.ROOT, "est. round-trip cost %s = %.0f%% of target reward (max %.0f%%)", cost.setScale(2, RoundingMode.HALF_EVEN), ratio * 100, o.maxCostToRewardRatio() * 100));
        add(checks, "NO_MARGIN_NEEDED", true, "long option: full premium paid up-front; worst case loss = total premium " + perLotPremium.multiply(BigDecimal.valueOf(lots)).setScale(0, RoundingMode.HALF_EVEN)
                + " (stop orders may gap through)");
        return qty;
    }

    private int countOpenedToday(List<PositionRec> open, LocalDate day) {
        // positions still open that were opened today were counted in daily trades only once closed
        return (int) open.stream().filter(p -> session.dateOf(p.openedAt()).equals(day)).count();
    }

    private int pendingEntries(List<OrderRec> pending) {
        return (int) pending.stream().filter(o -> o.purpose() == com.aiinvestor.domain.Enums.OrderPurpose.ENTRY).count();
    }

    private static void add(List<CheckResult> l, String name, boolean ok, String detail) { l.add(new CheckResult(name, ok, detail)); }
}
