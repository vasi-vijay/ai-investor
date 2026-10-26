package com.aiinvestor.paper;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Enums.*;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.engine.ExecutionAssumptions;
import com.aiinvestor.engine.RunContext;
import com.aiinvestor.paper.PaperBroker.EntryRequest;
import com.aiinvestor.persistence.DailyStateRepository;
import com.aiinvestor.persistence.RunRepository.Run;
import com.aiinvestor.persistence.TradeRepository;
import com.aiinvestor.persistence.TradeRepository.OrderRec;
import com.aiinvestor.persistence.TradeRepository.PositionRec;
import com.aiinvestor.runtime.ReconciliationService;
import com.aiinvestor.support.AbstractDbTest;
import com.aiinvestor.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PaperBrokerTest extends AbstractDbTest {
    @Autowired PaperBroker broker;
    @Autowired TradeRepository trades;
    @Autowired DailyStateRepository daily;
    @Autowired ReconciliationService reconciliation;
    @Autowired Fixtures fx;
    @Autowired AppProperties props;

    Run run; RunContext ctx; Instrument eq; Instant t0;

    @BeforeEach
    void setUp() {
        run = fx.run(RunMode.PAPER, AiMode.RULES_ONLY);
        ctx = fx.ctx(run);
        eq = fx.equity("PB1");
        t0 = fx.tuesday1000();
    }

    private Candle bar(int minuteOffset, double o, double h, double l, double c, long vol) {
        return new Candle(t0.plusSeconds(60L * minuteOffset), o, h, l, c, vol);
    }

    private Instant sig() { return t0; }

    private OrderRec entry(RunContext c, int qty, double ref, double stop, double target) {
        return broker.submitEntry(c, new EntryRequest("sig-" + qty + "-" + ref, "TEST", eq, Direction.LONG, qty, Fixtures.bd(ref), Fixtures.bd(stop), Fixtures.bd(target), sig())).orElseThrow();
    }

    private RunContext withExec(ExecutionAssumptions x) {
        return new RunContext(run.id(), RunMode.PAPER, AiMode.RULES_ONLY, run.initialCapital(), props.risk(), x, "MOCK", null, false);
    }

    private ExecutionAssumptions exec(OrderType type, double part) {
        return new ExecutionAssumptions(5, 0.5, part, 5, type, 3, 1.5);
    }

    @Test
    void marketEntryFillsOnTheNextBarOpenWithAdverseSlippage() {
        OrderRec o = entry(ctx, 100, 100, 99, 102);
        broker.processBars(ctx, eq, List.of(bar(-1, 90, 91, 89, 90, 100_000)));      // before the order existed: must be ignored
        assertThat(trades.order(o.id()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING);
        broker.processBars(ctx, eq, List.of(bar(0, 100, 100.4, 99.6, 100.2, 100_000)));
        OrderRec filled = trades.order(o.id()).orElseThrow();
        assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(filled.avgFillPrice()).isEqualByComparingTo("100.05");              // 100 * (1 + 5bps) rounded UP to tick
        Map<String, Object> ex = jdbc.sql("SELECT * FROM trade_executions WHERE order_id=?").param(o.id()).query().singleRow();
        assertThat(((BigDecimal) ex.get("market_price"))).isEqualByComparingTo("100");     // observed price kept separate from the simulated fill
        assertThat(((BigDecimal) ex.get("fill_price"))).isEqualByComparingTo("100.05");
        assertThat(((BigDecimal) ex.get("slippage_amount"))).isEqualByComparingTo("5.00");
        assertThat(((BigDecimal) ex.get("total_costs")).signum()).isPositive();
        assertThat(com.aiinvestor.persistence.Db.bool(ex.get("simulated"))).isTrue();
        PositionRec p = trades.openPositions(run.id()).get(0);
        assertThat(p.avgEntry()).isEqualByComparingTo("100.05");
        assertThat(p.openQuantity()).isEqualTo(100);
    }

    @Test
    void largeOrdersFillPartiallyByVolumeParticipation() {
        RunContext c = withExec(exec(OrderType.MARKET, 0.10));
        OrderRec o = entry(c, 5000, 100, 90, 120);
        broker.processBars(c, eq, List.of(bar(0, 100, 100.5, 99.5, 100, 20_000)));      // cap = 10% * 20000 = 2000
        OrderRec o1 = trades.order(o.id()).orElseThrow();
        assertThat(o1.status()).isEqualTo(OrderStatus.PARTIAL);
        assertThat(o1.filledQuantity()).isEqualTo(2000);
        assertThat(trades.openPositions(run.id()).get(0).openQuantity()).isEqualTo(2000);
        broker.processBars(c, eq, List.of(bar(1, 101, 101.5, 100.5, 101, 20_000), bar(2, 102, 102.5, 101.5, 102, 20_000)));
        OrderRec o3 = trades.order(o.id()).orElseThrow();
        assertThat(o3.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(o3.filledQuantity()).isEqualTo(5000);
        assertThat(trades.openPositions(run.id()).get(0).openQuantity()).isEqualTo(5000);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_executions WHERE order_id=?").param(o.id()).query(Integer.class).single()).isEqualTo(3);
        assertThat(reconciliation.endOfDay(run.id(), "PAPER", Fixtures.TUESDAY).stream().filter(s -> s.contains("filled quantity")).toList()).isEmpty();
    }

    @Test
    void unfilledRemainderIsCancelledAfterTheOrderTtl() {
        RunContext c = withExec(exec(OrderType.MARKET, 0.10));
        OrderRec o = entry(c, 5000, 100, 90, 120);
        broker.processBars(c, eq, List.of(bar(0, 100, 100.5, 99.5, 100, 20_000), bar(6, 100, 100.5, 99.5, 100, 20_000)));   // second bar is past the 5m TTL
        OrderRec r = trades.order(o.id()).orElseThrow();
        assertThat(r.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(r.filledQuantity()).isEqualTo(2000);
        assertThat(trades.openOrders(run.id())).isEmpty();
    }

    @Test
    void orderThatNeverFillsIsCancelledAndTheSignalRecordedAsNotFilled() {
        RunContext c = withExec(exec(OrderType.MARKET, 0.10));
        OrderRec o = entry(c, 100, 100, 99, 102);
        broker.processBars(c, eq, List.of(bar(0, 100, 100.5, 99.5, 100, 0), bar(6, 100, 100.5, 99.5, 100, 0)));     // zero volume: no liquidity
        assertThat(trades.order(o.id()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(trades.openPositions(run.id())).isEmpty();
    }

    @Test
    void limitOrdersFillOnlyWhenTouchedAndGapsGiveTheBetterPrice() {
        RunContext c = withExec(exec(OrderType.LIMIT, 0.10));
        OrderRec o = entry(c, 100, 100, 95, 110);
        broker.processBars(c, eq, List.of(bar(0, 101, 102, 100.5, 101.5, 100_000)));                 // low 100.5 > limit 100: not touched
        assertThat(trades.order(o.id()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING);
        broker.processBars(c, eq, List.of(bar(1, 100.4, 100.6, 99.8, 100.1, 100_000)));              // touches: fill at the limit
        assertThat(trades.order(o.id()).orElseThrow().avgFillPrice()).isEqualByComparingTo("100");

        OrderRec gap = broker.submitEntry(c, new EntryRequest("gap", "TEST", fx.equity("PB2"), Direction.LONG, 100, Fixtures.bd(100), Fixtures.bd(95), Fixtures.bd(110), t0.plusSeconds(120))).orElseThrow();
        Instrument pb2 = fx.equity("PB2");
        broker.processBars(c, pb2, List.of(new Candle(t0.plusSeconds(120), 99.5, 100.2, 99.0, 99.8, 100_000)));   // opens below the limit: price improvement
        assertThat(trades.order(gap.id()).orElseThrow().avgFillPrice()).isEqualByComparingTo("99.5");
    }

    private PositionRec openLong(double entryPx, double stop, double target) {
        OrderRec o = entry(ctx, 100, entryPx, stop, target);
        broker.processBars(ctx, eq, List.of(bar(0, entryPx, entryPx + 0.2, entryPx - 0.2, entryPx, 1_000_000)));
        return trades.openPositions(run.id()).get(0);
    }

    @Test
    void stopLossExitIncludesSlippageCostsAndUpdatesDailyPnlAndTheLock() {
        PositionRec p = openLong(100, 99, 102);
        broker.processBars(ctx, eq, List.of(bar(1, 100, 100.3, 98.7, 99, 1_000_000)));
        PositionRec closed = trades.position(p.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo("CLOSED");
        // entry 100.05, stop 99 -> sell fill = floor(99*(1-5bps)) = 98.95 ; gross = (98.95-100.05)*100 = -110
        assertThat(closed.grossPnl()).isEqualByComparingTo("-110.00");
        assertThat(closed.costs().signum()).isPositive();
        Map<String, Object> row = jdbc.sql("SELECT exit_reason, realized_pnl_net, avg_exit_price FROM positions WHERE id=?").param(p.id()).query().singleRow();
        assertThat(row.get("exit_reason")).isEqualTo("STOP_LOSS");
        assertThat(((BigDecimal) row.get("realized_pnl_net")).doubleValue()).isCloseTo(-110.0 - closed.costs().doubleValue(), within(0.011));
        var day = daily.get(run.id(), Fixtures.TUESDAY);
        assertThat(day.trades()).isEqualTo(1);
        assertThat(day.losses()).isEqualTo(1);
        assertThat(day.realizedNet()).isEqualByComparingTo((BigDecimal) row.get("realized_pnl_net"));
    }

    @Test
    void targetExitFillsAtTargetWithoutSlippage() {
        PositionRec p = openLong(100, 99, 102);
        broker.processBars(ctx, eq, List.of(bar(1, 100.5, 102.4, 100.3, 102.2, 1_000_000)));
        PositionRec closed = trades.position(p.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.grossPnl()).isEqualByComparingTo("195.00");                       // (102 - 100.05) * 100
        assertThat(jdbc.sql("SELECT exit_reason FROM positions WHERE id=?").param(p.id()).query(String.class).single()).isEqualTo("TARGET");
    }

    @Test
    void whenAStopAndTargetAreBothInsideOneBarTheStopWinsConservatively() {
        PositionRec p = openLong(100, 99, 102);
        broker.processBars(ctx, eq, List.of(bar(1, 100.5, 103, 98.5, 100, 1_000_000)));
        assertThat(jdbc.sql("SELECT exit_reason FROM positions WHERE id=?").param(p.id()).query(String.class).single()).isEqualTo("STOP_LOSS");
    }

    @Test
    void gapThroughTheStopFillsAtTheOpenNotAtTheStop() {
        PositionRec p = openLong(100, 99, 102);
        broker.processBars(ctx, eq, List.of(bar(1, 97, 97.5, 96, 96.5, 1_000_000)));
        BigDecimal exit = jdbc.sql("SELECT avg_exit_price FROM positions WHERE id=?").param(p.id()).query(BigDecimal.class).single();
        assertThat(exit.doubleValue()).isLessThan(97.0);
    }

    @Test
    void exitsAreLimitedByVolumeAndContinueOnLaterBars() {
        RunContext c = withExec(exec(OrderType.MARKET, 0.10));
        OrderRec o = entry(c, 1000, 100, 99, 105);
        broker.processBars(c, eq, List.of(bar(0, 100, 100.2, 99.8, 100, 1_000_000)));
        PositionRec p = trades.openPositions(run.id()).get(0);
        broker.processBars(c, eq, List.of(bar(1, 99, 99.5, 98, 98.5, 4_000)));          // stop hit but only 400 units tradable
        assertThat(trades.position(p.id()).orElseThrow().openQuantity()).isEqualTo(600);
        assertThat(trades.position(p.id()).orElseThrow().status()).isEqualTo("OPEN");
        broker.processBars(c, eq, List.of(bar(2, 98, 98.5, 97, 97.5, 1_000_000)));
        assertThat(trades.position(p.id()).orElseThrow().status()).isEqualTo("CLOSED");
        assertThat(o.quantity()).isEqualTo(1000);
    }

    @Test
    void reprocessingTheSameBarsDoesNotDoubleExit() {
        PositionRec p = openLong(100, 99, 102);
        List<Candle> bars = List.of(bar(1, 100, 100.3, 98.7, 99, 1_000_000));
        broker.processBars(ctx, eq, bars);
        broker.processBars(ctx, eq, bars);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_orders WHERE position_id=? AND purpose='STOP_LOSS'").param(p.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(daily.get(run.id(), Fixtures.TUESDAY).trades()).isEqualTo(1);
    }

    @Test
    void duplicateEntryOrdersAreSuppressedByTheClientOrderId() {
        EntryRequest r = new EntryRequest("same-signal", "TEST", eq, Direction.LONG, 10, Fixtures.bd(100), Fixtures.bd(99), Fixtures.bd(102), t0);
        assertThat(broker.submitEntry(ctx, r)).isPresent();
        assertThat(broker.submitEntry(ctx, r)).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_orders WHERE run_id=?").param(run.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void squareOffForcesAFlatBookAndReconciliationIsClean() {
        PositionRec p = openLong(100, 90, 120);
        broker.squareOff(ctx, eq, 101.0, fx.at(Fixtures.TUESDAY, 15, 15), "EOD_SQUARE_OFF");
        assertThat(trades.position(p.id()).orElseThrow().status()).isEqualTo("CLOSED");
        assertThat(trades.openPositions(run.id())).isEmpty();
        assertThat(reconciliation.endOfDay(run.id(), "PAPER", Fixtures.TUESDAY)).isEmpty();
    }

    @Test
    void everyPaperRowIsFlaggedSimulated() {
        openLong(100, 99, 102);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_orders WHERE simulated=0").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_executions WHERE simulated=0").query(Integer.class).single()).isZero();
    }
}
