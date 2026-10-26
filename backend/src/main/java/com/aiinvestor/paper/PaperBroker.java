package com.aiinvestor.paper;

import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Enums.*;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Money;
import com.aiinvestor.engine.ExecutionAssumptions;
import com.aiinvestor.engine.RunContext;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.persistence.Db;
import com.aiinvestor.persistence.InstrumentRepository;
import com.aiinvestor.persistence.SignalRepository;
import com.aiinvestor.persistence.TradeRepository;
import com.aiinvestor.persistence.TradeRepository.OrderRec;
import com.aiinvestor.persistence.TradeRepository.PositionRec;
import com.aiinvestor.risk.RiskGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Simulated execution venue. Nothing here ever contacts a broker; every row it writes is flagged simulated=1 and a
 * simulated fill is never reported as a Groww execution. Fills are derived from OBSERVED market bars (market_price)
 * plus configured slippage (fill_price), so the two are always distinguishable.
 *
 * Bar semantics (look-ahead free): an order created at decision time T can only fill on bars starting at or after T.
 * Within a bar, if both stop and target would be touched, the stop is assumed to hit first (conservative).
 */
@Component
public class PaperBroker {
    private static final Logger log = LoggerFactory.getLogger(PaperBroker.class);

    public record EntryRequest(String signalId, String strategy, Instrument instrument, Direction direction, int quantity,
                               BigDecimal referencePrice, BigDecimal stop, BigDecimal target, Instant now) {}

    private final TradeRepository trades;
    private final InstrumentRepository instruments;
    private final SignalRepository signals;
    private final RiskGuard guard;
    private final CostModel costs;
    private final MarketSession session;

    public PaperBroker(TradeRepository trades, InstrumentRepository instruments, SignalRepository signals,
                       RiskGuard guard, CostModel costs, MarketSession session) {
        this.trades = trades;
        this.instruments = instruments;
        this.signals = signals;
        this.guard = guard;
        this.costs = costs;
        this.session = session;
    }

    /** Creates a PENDING simulated entry order. Empty if an order with the same client id already exists (duplicate). */
    public Optional<OrderRec> submitEntry(RunContext run, EntryRequest r) {
        boolean isLong = r.direction() == Direction.LONG;
        OrderType type = run.exec().entryOrderType();
        BigDecimal initialRisk = r.referencePrice().subtract(r.stop()).abs().multiply(BigDecimal.valueOf(r.quantity()));
        OrderRec o = new OrderRec(Db.newId(), run.runId(), run.mode(), r.signalId() + ":ENTRY", r.signalId(), null,
                r.instrument().id(), isLong ? Side.BUY : Side.SELL, type, OrderPurpose.ENTRY, r.quantity(),
                type == OrderType.LIMIT ? r.referencePrice() : null, OrderStatus.PENDING, 0, null, r.now(),
                r.stop(), r.target(), Money.money(initialRisk), r.strategy());
        return trades.insertOrder(o, true);
    }

    /** Process chronological 1-minute bars for one instrument: entry fills, TTL cancels, stop/target exits. */
    public void processBars(RunContext run, Instrument instr, List<Candle> bars) {
        for (Candle bar : bars) {
            for (OrderRec o : pendingEntries(run, instr)) processEntry(run, instr, o, bar);
            Optional<PositionRec> pos = openPosition(run, instr);
            if (pos.isPresent() && !bar.start().isBefore(pos.get().openedAt())) processExit(run, instr, pos.get(), bar);
        }
    }

    /** Forced end-of-session flat: closes at {@code price} (adverse slippage applied) ignoring the participation cap. */
    public void squareOff(RunContext run, Instrument instr, double price, Instant at, String reason) {
        for (OrderRec o : pendingEntries(run, instr)) cancelRemaining(o, "cancelled: " + reason, at);
        openPosition(run, instr).ifPresent(p -> exit(run, instr, p, OrderPurpose.SQUARE_OFF, p.openQuantity(),
                BigDecimal.valueOf(price), at, reason));
    }

    public void cancelPendingEntries(RunContext run, String reason, Instant at) {
        for (OrderRec o : trades.openOrders(run.runId())) {
            if (o.purpose() == OrderPurpose.ENTRY) cancelRemaining(o, reason, at);
        }
    }

    // ---------------------------------------------------------------- entries

    private List<OrderRec> pendingEntries(RunContext run, Instrument instr) {
        List<OrderRec> out = new ArrayList<>();
        for (OrderRec o : trades.openOrders(run.runId())) {
            if (o.purpose() == OrderPurpose.ENTRY && o.instrumentId() == instr.id()) out.add(o);
        }
        return out;
    }

    private Optional<PositionRec> openPosition(RunContext run, Instrument instr) {
        return trades.openPositions(run.runId()).stream().filter(p -> p.instrumentId() == instr.id()).findFirst();
    }

    private void processEntry(RunContext run, Instrument instr, OrderRec o, Candle bar) {
        if (bar.start().isBefore(o.createdAt())) return;               // order did not exist yet: no look-ahead
        ExecutionAssumptions x = run.exec();
        if (!bar.start().isBefore(o.createdAt().plusSeconds(x.entryTtlMinutes() * 60L))) {
            cancelRemaining(o, "entry order expired after " + x.entryTtlMinutes() + "m", bar.start());
            return;
        }
        if (!session.allowsNewEntries(bar.start())) {
            cancelRemaining(o, "session no longer accepts new entries", bar.start());
            return;
        }
        boolean buy = o.side() == Side.BUY;
        BigDecimal ref;
        BigDecimal fillPrice;
        if (o.type() == OrderType.MARKET) {
            ref = BigDecimal.valueOf(bar.open());
            fillPrice = slip(run, instr, o.side(), ref);
        } else {
            double lim = o.limitPrice().doubleValue();
            boolean touched = buy ? bar.low() <= lim : bar.high() >= lim;
            if (!touched) return;
            double px = buy ? Math.min(lim, bar.open()) : Math.max(lim, bar.open());
            ref = BigDecimal.valueOf(px);
            fillPrice = Money.roundToTick(ref, instr.tickSize());
        }
        int remaining = o.quantity() - o.filledQuantity();
        int qty = Math.min(remaining, volumeCap(run, instr, bar));
        if (qty <= 0) return;                                         // no liquidity this bar; keep waiting until TTL
        applyEntryFill(run, instr, o, qty, ref, fillPrice, bar.start());
    }

    private void applyEntryFill(RunContext run, Instrument instr, OrderRec o, int qty, BigDecimal market, BigDecimal fill, Instant at) {
        CostModel.Costs c = costs.forFill(instr.type(), o.side(), fill, qty);
        int filled = o.filledQuantity() + qty;
        BigDecimal prevValue = o.avgFillPrice() == null ? BigDecimal.ZERO : o.avgFillPrice().multiply(BigDecimal.valueOf(o.filledQuantity()));
        BigDecimal avg = prevValue.add(fill.multiply(BigDecimal.valueOf(qty))).divide(BigDecimal.valueOf(filled), 4, RoundingMode.HALF_EVEN);
        OrderStatus status = filled >= o.quantity() ? OrderStatus.FILLED : OrderStatus.PARTIAL;
        trades.updateOrder(o.id(), status, filled, avg, null, at);
        BigDecimal slipAmt = fill.subtract(market).abs().multiply(BigDecimal.valueOf(qty));
        trades.insertExecution(Db.newId(), run.runId(), run.mode(), o.id(), instr.id(), o.side(), qty, Money.price4(market), fill,
                Money.money(slipAmt), c.brokerage(), c.statutory(), c.total(), at);

        Direction dir = o.side() == Side.BUY ? Direction.LONG : Direction.SHORT;
        if (o.positionId() == null) {
            String pid = Db.newId();
            PositionRec p = new PositionRec(pid, run.runId(), run.mode(), instr.id(), o.signalId(), o.planStrategy(), dir, qty, qty, fill,
                    o.planStop(), o.planTarget(), "OPEN", at, BigDecimal.ZERO, c.total(), o.planInitialRisk(), BigDecimal.ZERO, 0);
            trades.insertPosition(p);
            trades.attachPosition(o.id(), pid);
            if (o.signalId() != null) signals.finalize(o.signalId(), FinalStatus.APPROVED, null, o.id(), pid);
        } else {
            PositionRec p = trades.position(o.positionId()).orElseThrow();
            int newQty = p.quantity() + qty;
            int newOpen = p.openQuantity() + qty;
            BigDecimal newAvg = p.avgEntry().multiply(BigDecimal.valueOf(p.quantity())).add(fill.multiply(BigDecimal.valueOf(qty)))
                    .divide(BigDecimal.valueOf(newQty), 4, RoundingMode.HALF_EVEN);
            trades.savePosition(new PositionRec(p.id(), p.runId(), p.runMode(), p.instrumentId(), p.signalId(), p.strategy(), p.side(),
                    newQty, newOpen, newAvg, p.stop(), p.target(), "OPEN", p.openedAt(), p.grossPnl(), p.costs().add(c.total()),
                    p.initialRisk(), BigDecimal.ZERO, 0), null, null, null);
        }
        log.debug("paper entry fill {} {} x{} @ {} (market {})", o.side(), instr.tradingSymbol(), qty, fill, market);
    }

    private void cancelRemaining(OrderRec o, String reason, Instant at) {
        trades.updateOrder(o.id(), OrderStatus.CANCELLED, o.filledQuantity(), o.avgFillPrice(), reason, at);
        if (o.filledQuantity() == 0 && o.signalId() != null) {
            signals.finalize(o.signalId(), FinalStatus.REJECTED, "entry order never filled: " + reason, o.id(), null);
            signals.recordOutcome(o.signalId(), "NOT_FILLED", BigDecimal.ZERO, null);
        }
    }

    // ---------------------------------------------------------------- exits

    private void processExit(RunContext run, Instrument instr, PositionRec p, Candle bar) {
        boolean isLong = p.side() == Direction.LONG;
        double stop = p.stop() == null ? Double.NaN : p.stop().doubleValue();
        double target = p.target() == null ? Double.NaN : p.target().doubleValue();
        boolean stopHit = !Double.isNaN(stop) && (isLong ? bar.low() <= stop : bar.high() >= stop);
        boolean targetHit = !Double.isNaN(target) && (isLong ? bar.high() >= target : bar.low() <= target);
        if (!stopHit && !targetHit) return;
        boolean gapThroughTarget = !Double.isNaN(target) && (isLong ? bar.open() >= target : bar.open() <= target);
        boolean gapThroughStop = !Double.isNaN(stop) && (isLong ? bar.open() <= stop : bar.open() >= stop);
        OrderPurpose purpose;
        double px;
        if (gapThroughTarget) { purpose = OrderPurpose.TARGET; px = bar.open(); }
        else if (stopHit) { purpose = OrderPurpose.STOP_LOSS; px = gapThroughStop ? bar.open() : stop; }   // stop wins ties
        else { purpose = OrderPurpose.TARGET; px = target; }
        int qty = Math.min(p.openQuantity(), volumeCap(run, instr, bar));
        if (qty <= 0) return;
        exit(run, instr, p, purpose, qty, BigDecimal.valueOf(px), bar.end(1), purpose.name());
    }

    private void exit(RunContext run, Instrument instr, PositionRec p, OrderPurpose purpose, int qty, BigDecimal marketPx, Instant at, String reason) {
        boolean isLong = p.side() == Direction.LONG;
        Side side = isLong ? Side.SELL : Side.BUY;
        BigDecimal fill = purpose == OrderPurpose.TARGET ? Money.roundToTick(marketPx, instr.tickSize()) : slip(run, instr, side, marketPx);
        CostModel.Costs c = costs.forFill(instr.type(), side, fill, qty);
        String orderId = Db.newId();
        String clientId = "pos:" + p.id() + ":" + purpose + ":" + at.toEpochMilli() + ":" + p.openQuantity();
        OrderRec o = new OrderRec(orderId, run.runId(), run.mode(), clientId, p.signalId(), p.id(), instr.id(), side,
                purpose == OrderPurpose.TARGET ? OrderType.LIMIT : OrderType.MARKET, purpose, qty,
                purpose == OrderPurpose.TARGET ? p.target() : null, OrderStatus.FILLED, qty, fill, at, null, null, null, p.strategy());
        if (trades.insertOrder(o, true).isEmpty()) return;          // already processed (idempotent replay)
        BigDecimal slipAmt = fill.subtract(marketPx).abs().multiply(BigDecimal.valueOf(qty));
        trades.insertExecution(Db.newId(), run.runId(), run.mode(), orderId, instr.id(), side, qty, Money.price4(marketPx), fill,
                Money.money(slipAmt), c.brokerage(), c.statutory(), c.total(), at);

        BigDecimal perUnit = isLong ? fill.subtract(p.avgEntry()) : p.avgEntry().subtract(fill);
        BigDecimal gross = p.grossPnl().add(perUnit.multiply(BigDecimal.valueOf(qty)));
        BigDecimal totalCosts = p.costs().add(c.total());
        int open = p.openQuantity() - qty;
        if (open > 0) {
            trades.savePosition(new PositionRec(p.id(), p.runId(), p.runMode(), p.instrumentId(), p.signalId(), p.strategy(), p.side(),
                    p.quantity(), open, p.avgEntry(), p.stop(), p.target(), "OPEN", p.openedAt(), Money.money(gross), totalCosts,
                    p.initialRisk(), BigDecimal.ZERO, 0), null, null, null);
            return;
        }
        BigDecimal net = Money.money(gross.subtract(totalCosts));
        trades.savePosition(new PositionRec(p.id(), p.runId(), p.runMode(), p.instrumentId(), p.signalId(), p.strategy(), p.side(),
                p.quantity(), 0, p.avgEntry(), p.stop(), p.target(), "CLOSED", p.openedAt(), Money.money(gross), Money.money(totalCosts),
                p.initialRisk(), BigDecimal.ZERO, 0), at, reason, fill);
        // cancel any entry remainder for this position's order
        guard.onTradeClosed(run, at, net, Money.money(totalCosts), p.signalId());
        if (p.signalId() != null) {
            String outcome = net.signum() > 0 ? "WIN" : net.signum() < 0 ? "LOSS" : "BREAKEVEN";
            signals.recordOutcome(p.signalId(), outcome + ":" + reason, net, null);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Max units tradable in this bar under the participation cap (lot multiples for options). */
    private int volumeCap(RunContext run, Instrument instr, Candle bar) {
        long cap = (long) Math.floor(run.exec().maxParticipation() * bar.volume());
        if (instr.isOption()) cap = (cap / instr.lotSize()) * instr.lotSize();
        return (int) Math.min(cap, Integer.MAX_VALUE);
    }

    /** Adverse slippage then adverse tick rounding (BUY rounds up, SELL rounds down). */
    BigDecimal slip(RunContext run, Instrument instr, Side side, BigDecimal ref) {
        double frac = instr.isOption() ? run.exec().optionSlippagePct() / 100.0 : run.exec().equitySlippageBps() / 10_000.0;
        BigDecimal factor = BigDecimal.valueOf(side == Side.BUY ? 1 + frac : 1 - frac);
        BigDecimal px = ref.multiply(factor);
        return side == Side.BUY ? Money.ceilToTick(px, instr.tickSize()) : Money.floorToTick(px, instr.tickSize()).max(instr.tickSize());
    }
}
