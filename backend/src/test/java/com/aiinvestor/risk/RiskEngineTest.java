package com.aiinvestor.risk;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Enums.*;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.engine.RunContext;
import com.aiinvestor.persistence.DailyStateRepository;
import com.aiinvestor.persistence.RunRepository.Run;
import com.aiinvestor.persistence.TradeRepository;
import com.aiinvestor.risk.RiskModels.CheckResult;
import com.aiinvestor.risk.RiskModels.RiskDecision;
import com.aiinvestor.risk.RiskModels.RiskRequest;
import com.aiinvestor.support.AbstractDbTest;
import com.aiinvestor.support.Fixtures;
import com.aiinvestor.support.TestProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskEngineTest extends AbstractDbTest {
    @Autowired RiskEngine risk;
    @Autowired Fixtures fx;
    @Autowired KillSwitch kill;
    @Autowired RiskGuard guard;
    @Autowired DailyStateRepository daily;
    @Autowired TradeRepository trades;
    @Autowired AppProperties props;

    Run run; RunContext ctx; Instrument eq; Instant now;

    @BeforeEach
    void setUp() {
        run = fx.run(RunMode.PAPER, AiMode.RULES_ONLY);
        ctx = fx.ctx(run);
        eq = fx.equity("EQT1");
        now = fx.tuesday1000();
    }

    private Quote q(double bid, double ask) { return fx.quote("EQT1", bid, ask, now, 100_000); }

    private static CheckResult check(RiskDecision d, String name) {
        return d.checks().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no check " + name + " in " + d.checks()));
    }

    // ---------------------------------------------------------------- equity sizing

    @Test
    void equitySizingFollowsRiskBudgetAndExposureCaps() {
        RiskDecision d = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, q(99.99, 100.01), now, 100_000));
        assertThat(d.approved()).as(d.rejectionSummary()).isTrue();
        assertThat(d.quantity()).isEqualTo(2000);                         // min(risk 2000/1, exposure 200000/100, alloc 5000, liquidity 5000)
        assertThat(d.riskAmount()).isEqualByComparingTo("2000");
        assertThat(d.notional()).isEqualByComparingTo("200000");
    }

    @Test
    void tighterStopDoesNotBreakTheExposureCap() {
        RiskDecision d = risk.evaluate(ctx, fx.equityLong(eq, 100, 99.5, 101, q(99.99, 100.01), now, 100_000));
        assertThat(d.approved()).isTrue();
        assertThat(d.quantity()).isEqualTo(2000);                         // risk allows 4000 but 20% per-instrument cap = 2000 shares
    }

    @Test
    void stopTighterThanMinimumTicksIsRejected() {
        RiskDecision d = risk.evaluate(ctx, fx.equityLong(eq, 100, 99.9, 100.3, q(99.99, 100.01), now, 100_000));
        assertThat(check(d, "STOP_DISTANCE").passed()).isFalse();
        assertThat(d.approved()).isFalse();
    }

    @Test
    void wideSpreadIsRejected() {
        RiskDecision d = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, q(99.0, 101.0), now, 100_000));
        assertThat(check(d, "SPREAD").passed()).isFalse();
        assertThat(d.approved()).isFalse();
    }

    @Test
    void insufficientLiquidityIsRejected() {
        RiskDecision d = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, q(99.99, 100.01), now, 1000));
        assertThat(check(d, "LIQUIDITY").passed()).isFalse();
        assertThat(d.approved()).isFalse();
    }

    @Test
    void lowRiskRewardIsRejected() {
        RiskDecision d = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 100.5, q(99.99, 100.01), now, 100_000));
        assertThat(check(d, "RISK_REWARD").passed()).isFalse();
        assertThat(d.approved()).isFalse();
    }

    @Test
    void staleQuoteIsRejectedAndMissingQuoteToo() {
        Quote stale = fx.quote("EQT1", 99.99, 100.01, now.minus(Duration.ofMinutes(5)), 100_000);
        RiskDecision d = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, stale, now, 100_000));
        assertThat(check(d, "DATA_FRESHNESS").passed()).isFalse();
        assertThat(d.approved()).isFalse();
        RiskDecision none = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, null, now, 100_000));
        assertThat(check(none, "DATA_FRESHNESS").passed()).isFalse();
        assertThat(none.approved()).isFalse();
    }

    @Test
    void marketSessionIsValidated() {
        Instant night = fx.at(Fixtures.TUESDAY, 20, 0), late = fx.at(Fixtures.TUESDAY, 15, 10), weekend = fx.at(LocalDate.of(2025, 3, 8), 10, 0);
        for (Instant t : new Instant[]{night, late, weekend}) {
            RiskDecision d = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, fx.quote("EQT1", 99.99, 100.01, t, 100_000), t, 100_000));
            assertThat(check(d, "MARKET_SESSION").passed()).as("at %s", t).isFalse();
            assertThat(d.approved()).isFalse();
        }
    }

    @Test
    void shortSellingCanBeDisabledByConfiguration() {
        AppProperties.Risk noShort = TestProps.with(Map.of("app.risk.equity.allow-short", "false")).risk();
        RiskRequest r = new RiskRequest("s", eq, Direction.SHORT, Side.SELL, Fixtures.bd(100), Fixtures.bd(101), Fixtures.bd(98), q(99.99, 100.01), false, now, 100_000, 0, 0);
        RiskDecision d = risk.evaluate(fx.ctx(run, noShort), r);
        assertThat(check(d, "EQUITY_SHORT_ALLOWED").passed()).isFalse();
        assertThat(d.approved()).isFalse();
        assertThat(risk.evaluate(ctx, r).approved()).isTrue();
    }

    // ---------------------------------------------------------------- options

    private Instrument opt(int lot, LocalDate expiry) { return fx.option("OPT-" + lot + "-" + expiry, expiry, lot, OptionType.CE); }
    private Quote oq(String s, double bid, double ask, long vol) { return fx.quote(s, bid, ask, now, vol); }

    @Test
    void longOptionSizingUsesLotsAndPremiumAtRisk() {
        Instrument o = opt(75, Fixtures.TUESDAY.plusDays(5));
        RiskDecision d = risk.evaluate(ctx, fx.optionLong(o, 100, 75, 137.5, oq(o.tradingSymbol(), 99.5, 100.5, 5000), now, 0.02));
        assertThat(d.approved()).as(d.rejectionSummary()).isTrue();
        assertThat(d.quantity()).isEqualTo(75);                          // loss budget 2000 / (25*75=1875) = 1 lot
        assertThat(d.quantity() % o.lotSize()).isZero();
        assertThat(check(d, "SIZING").detail()).contains("total premium at risk 7500");
    }

    @Test
    void optionContractWithMissingMetadataIsRejected() {
        Instrument noExpiry = new Instrument(9001L, "NSE", "FNO", InstrumentType.OPTION, "BAD1", "BAD1", "bad", "NIFTY", null, new BigDecimal("24500"), OptionType.CE, 75, new BigDecimal("0.05"), "x", true, "TEST");
        Instrument noStrike = new Instrument(9002L, "NSE", "FNO", InstrumentType.OPTION, "BAD2", "BAD2", "bad", "NIFTY", Fixtures.TUESDAY.plusDays(5), null, OptionType.CE, 75, new BigDecimal("0.05"), "x", true, "TEST");
        Instrument noLot = new Instrument(9003L, "NSE", "FNO", InstrumentType.OPTION, "BAD3", "BAD3", "bad", "NIFTY", Fixtures.TUESDAY.plusDays(5), new BigDecimal("24500"), OptionType.CE, 0, new BigDecimal("0.05"), "x", true, "TEST");
        for (Instrument bad : new Instrument[]{noExpiry, noStrike, noLot}) {
            RiskDecision d = risk.evaluate(ctx, fx.optionLong(bad, 100, 75, 137.5, oq(bad.tradingSymbol(), 99.5, 100.5, 5000), now, 0));
            assertThat(check(d, "CONTRACT_VALID").passed()).as(bad.tradingSymbol()).isFalse();
            assertThat(d.approved()).isFalse();
            assertThat(d.quantity()).isZero();
        }
    }

    @Test
    void expiredAndExpiryDayContractsAreRejected() {
        Instrument expired = opt(75, Fixtures.TUESDAY.minusDays(1)), today = opt(76, Fixtures.TUESDAY);
        assertThat(check(risk.evaluate(ctx, fx.optionLong(expired, 100, 75, 137.5, oq(expired.tradingSymbol(), 99.5, 100.5, 5000), now, 0)), "CONTRACT_NOT_EXPIRED").passed()).isFalse();
        RiskDecision d = risk.evaluate(ctx, fx.optionLong(today, 100, 75, 137.5, oq(today.tradingSymbol(), 99.5, 100.5, 5000), now, 0));
        assertThat(check(d, "DAYS_TO_EXPIRY").passed()).isFalse();
        assertThat(d.approved()).isFalse();
    }

    @Test
    void shortOptionsAreNeverApproved() {
        Instrument o = opt(75, Fixtures.TUESDAY.plusDays(5));
        RiskRequest r = new RiskRequest("s", o, Direction.SHORT, Side.SELL, Fixtures.bd(100), Fixtures.bd(125), Fixtures.bd(60), oq(o.tradingSymbol(), 99.5, 100.5, 5000), false, now, 0, 0, 0);
        RiskDecision d = risk.evaluate(ctx, r);
        assertThat(check(d, "SHORT_OPTIONS_DISABLED").passed()).isFalse();
        assertThat(d.approved()).isFalse();
        assertThat(d.quantity()).isZero();
    }

    @Test
    void optionLiquiditySpreadPremiumAndRapidMoveFilters() {
        Instrument o = opt(75, Fixtures.TUESDAY.plusDays(5));
        String s = o.tradingSymbol();
        assertThat(check(risk.evaluate(ctx, fx.optionLong(o, 100, 75, 137.5, oq(s, 95, 105, 5000), now, 0)), "SPREAD").passed()).as("10% spread").isFalse();
        assertThat(check(risk.evaluate(ctx, fx.optionLong(o, 100, 75, 137.5, oq(s, 99.5, 100.5, 50), now, 0)), "LIQUIDITY_VOLUME").passed()).as("low volume").isFalse();
        assertThat(check(risk.evaluate(ctx, fx.optionLong(o, 5, 3.75, 6.9, oq(s, 4.9, 5.1, 5000), now, 0)), "PREMIUM_RANGE").passed()).as("premium below minimum").isFalse();
        assertThat(check(risk.evaluate(ctx, fx.optionLong(o, 100, 75, 137.5, oq(s, 99.5, 100.5, 5000), now, 0.5)), "RAPID_PREMIUM_MOVE").passed()).as("50% premium move").isFalse();
        assertThat(check(risk.evaluate(ctx, fx.optionLong(o, 100.03, 75, 137.5, oq(s, 99.5, 100.5, 5000), now, 0)), "TICK_SIZE").passed()).as("tick alignment").isFalse();
    }

    @Test
    void optionTooLargeForTheLossBudgetIsRejectedNotShrunkBelowOneLot() {
        Instrument o = opt(75, Fixtures.TUESDAY.plusDays(5));
        // 75 units * 60 risk = 4500 per lot > 2000 loss budget
        RiskDecision d = risk.evaluate(ctx, fx.optionLong(o, 100, 40, 190, oq(o.tradingSymbol(), 99.5, 100.5, 5000), now, 0));
        assertThat(d.approved()).isFalse();
        assertThat(check(d, "SIZING").passed()).isFalse();
    }

    // ---------------------------------------------------------------- portfolio limits & locks

    private void closeTrade(double net) {
        guard.onTradeClosed(ctx, now, BigDecimal.valueOf(net), BigDecimal.valueOf(40), null);
    }

    @Test
    void maxTradesPerDayConsecutiveLossesAndOpenPositionsAreEnforced() {
        for (int i = 0; i < 3; i++) closeTrade(-100);
        RiskDecision d = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, q(99.99, 100.01), now, 100_000));
        assertThat(check(d, "CONSECUTIVE_LOSSES").passed()).isFalse();
        for (int i = 0; i < 8; i++) closeTrade(50);                    // wins reset the streak; total trades now 11
        RiskDecision d2 = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, q(99.99, 100.01), now, 100_000));
        assertThat(check(d2, "MAX_TRADES_PER_DAY").passed()).isFalse();
        assertThat(check(d2, "CONSECUTIVE_LOSSES").passed()).isTrue();
    }

    @Test
    void positionSizeDoesNotGrowAfterLossesAndShrinksNearTheDailyLimit() {
        int base = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, q(99.99, 100.01), now, 100_000)).quantity();
        closeTrade(-500); closeTrade(-500);
        int afterLosses = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, q(99.99, 100.01), now, 100_000)).quantity();
        assertThat(afterLosses).as("no martingale: size must not increase after losses").isLessThanOrEqualTo(base);
        closeTrade(-12_500);                                             // realised -13,500 of 15,000 limit -> room 1,500
        RiskDecision near = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, q(99.99, 100.01), now, 100_000));
        assertThat(near.quantity()).isLessThanOrEqualTo(1500);
    }

    @Test
    void dailyLossLimitLocksTradingAndTheLockPersistsAcrossRepositoryInstances() {
        closeTrade(-15_100);
        DailyStateRepository.DayState s = new DailyStateRepository(jdbc).get(run.id(), Fixtures.TUESDAY);   // "restart": fresh repository over the same database
        assertThat(s.locked()).isTrue();
        assertThat(s.lockReason()).contains("daily loss limit");
        RiskDecision d = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, q(99.99, 100.01), now, 100_000));
        assertThat(check(d, "DAILY_LOCK").passed()).isFalse();
        assertThat(d.approved()).isFalse();
        closeTrade(+20_000);                                             // a later profit must NOT unlock the day
        assertThat(daily.get(run.id(), Fixtures.TUESDAY).locked()).isTrue();
        assertThat(risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, q(99.99, 100.01), now, 100_000)).approved()).isFalse();
        // next trading day starts unlocked
        assertThat(daily.get(run.id(), Fixtures.TUESDAY.plusDays(1)).locked()).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM risk_events WHERE event_type='DAILY_LOCK'").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void killSwitchBlocksNewOrdersPersistsAndDoesNotAffectBacktests() {
        kill.set(true, "tester", "unit test");
        assertThat(kill.isEngaged()).isTrue();
        RiskDecision d = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, q(99.99, 100.01), now, 100_000));
        assertThat(check(d, "KILL_SWITCH").passed()).isFalse();
        assertThat(d.approved()).isFalse();
        assertThat(new com.aiinvestor.persistence.SettingsRepository(jdbc).get("kill_switch")).contains("ON");   // persisted in the DB
        Run bt = fx.run(RunMode.BACKTEST, AiMode.RULES_ONLY);
        assertThat(check(risk.evaluate(fx.ctx(bt), fx.equityLong(eq, 100, 99, 102, q(99.99, 100.01), now, 100_000)), "KILL_SWITCH").passed()).isTrue();
        kill.set(false, "tester", "release");
        assertThat(risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, q(99.99, 100.01), now, 100_000)).approved()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action LIKE 'KILL_SWITCH%'").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void duplicateInstrumentIsRejectedWhileAPositionOrOrderExists() {
        String posId = com.aiinvestor.persistence.Db.newId();
        trades.insertPosition(new TradeRepository.PositionRec(posId, run.id(), RunMode.PAPER, eq.id(), null, "X", Direction.LONG, 10, 10,
                Fixtures.bd(100), Fixtures.bd(99), Fixtures.bd(102), "OPEN", now, BigDecimal.ZERO, BigDecimal.ZERO, Fixtures.bd(10), BigDecimal.ZERO, 0));
        RiskDecision d = risk.evaluate(ctx, fx.equityLong(eq, 100, 99, 102, q(99.99, 100.01), now, 100_000));
        assertThat(check(d, "DUPLICATE_ORDER").passed()).isFalse();
        assertThat(d.approved()).isFalse();
    }

    @Test
    void everyFailedCheckIsReportedWithAnExactReason() {
        kill.set(true, "t", "t");
        RiskDecision d = risk.evaluate(ctx, fx.equityLong(eq, 100, 99.9, 100.2, q(98, 102), fx.at(Fixtures.TUESDAY, 20, 0), 10));
        assertThat(d.failures().size()).isGreaterThanOrEqualTo(5);
        assertThat(d.rejectionSummary()).contains("KILL_SWITCH").contains("MARKET_SESSION").contains("SPREAD").contains("LIQUIDITY");
        kill.set(false, "t", "t");
    }
}
