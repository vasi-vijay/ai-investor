package com.aiinvestor.live;

import com.aiinvestor.broker.BrokerAccountProvider;
import com.aiinvestor.broker.BrokerDtos.*;
import com.aiinvestor.broker.BrokerException;
import com.aiinvestor.broker.BrokerOrderProvider;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Enums.*;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.engine.ExecutionAssumptions;
import com.aiinvestor.engine.RunContext;
import com.aiinvestor.market.HistoricalDataProvider;
import com.aiinvestor.market.MarketDataProvider;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.market.TradingClock;
import com.aiinvestor.persistence.*;
import com.aiinvestor.persistence.RunRepository.Run;
import com.aiinvestor.persistence.TradeRepository.OrderRec;
import com.aiinvestor.risk.RiskEngine;
import com.aiinvestor.risk.RiskModels.RiskDecision;
import com.aiinvestor.risk.RiskModels.RiskRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * LIVE order workflow — manual approval of every order, no automatic retries.
 * propose → (human) approve → submit. Ambiguous submissions (timeout/5xx) are parked as UNKNOWN_RECONCILE and resolved
 * only by querying the broker by order_reference_id; they are NEVER resubmitted automatically.
 * Nothing here is reachable unless {@link LiveExecutionGate} passes; Claude/AI output has no path into this class.
 */
@Service
public class LiveOrderService {
    public static final String AUTH_PHRASE = "I AUTHORIZE LIVE TRADING WITH REAL MONEY";

    private final LiveExecutionGate gate;
    private final ObjectProvider<BrokerOrderProvider> orders;
    private final ObjectProvider<BrokerAccountProvider> account;
    private final ObjectProvider<MarketDataProvider> quotes;
    private final ObjectProvider<HistoricalDataProvider> history;
    private final RiskEngine risk;
    private final TradeRepository trades;
    private final InstrumentRepository instruments;
    private final RunRepository runs;
    private final SettingsRepository settings;
    private final AuditRepository audit;
    private final RiskEventRepository riskEvents;
    private final JdbcClient jdbc;
    private final AppProperties props;
    private final MarketSession session;
    private final TradingClock clock;
    private final ObjectMapper mapper;

    public LiveOrderService(LiveExecutionGate gate, ObjectProvider<BrokerOrderProvider> orders, ObjectProvider<BrokerAccountProvider> account,
                            ObjectProvider<MarketDataProvider> quotes, ObjectProvider<HistoricalDataProvider> history, RiskEngine risk,
                            TradeRepository trades, InstrumentRepository instruments, RunRepository runs, SettingsRepository settings,
                            AuditRepository audit, RiskEventRepository riskEvents, JdbcClient jdbc, AppProperties props,
                            MarketSession session, TradingClock clock, ObjectMapper mapper) {
        this.gate = gate; this.orders = orders; this.account = account; this.quotes = quotes; this.history = history; this.risk = risk;
        this.trades = trades; this.instruments = instruments; this.runs = runs; this.settings = settings; this.audit = audit;
        this.riskEvents = riskEvents; this.jdbc = jdbc; this.props = props; this.session = session; this.clock = clock; this.mapper = mapper;
    }

    public record ProposeRequest(long instrumentId, String side, String orderType, int quantity, BigDecimal limitPrice,
                                 BigDecimal stopLoss, BigDecimal target, String signalId) {}

    // ------------------------------------------------------------------ status & authorisation

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<LiveExecutionGate.Check> checks = gate.evaluate();
        m.put("account_scope", "LIVE");
        m.put("live_order_placement_enabled", checks.stream().allMatch(LiveExecutionGate.Check::ok));
        m.put("manual_approval_required", props.live().requireManualApproval());
        m.put("gate", checks);
        m.put("order_provider", orders.getIfAvailable() == null ? "NONE" : orders.getIfAvailable().sourceName());
        BrokerAccountProvider acc = account.getIfAvailable();
        if (acc != null && "GROWW".equals(acc.sourceName()) && acc.isConnected()) {
            try { m.put("live_funds", acc.funds()); } catch (RuntimeException e) { m.put("live_funds_error", e.getClass().getSimpleName()); }
        }
        m.put("pending_approvals", jdbc.sql("SELECT COUNT(*) FROM live_order_approvals WHERE status='PENDING_APPROVAL'").query(Integer.class).single());
        return m;
    }

    public void authorize(String actor, String confirmation) {
        if (!props.live().enabled()) throw new IllegalStateException("live trading is not enabled in configuration (app.live.enabled=false)");
        if (!AUTH_PHRASE.equals(confirmation)) throw new IllegalArgumentException("confirmation phrase mismatch");
        settings.set(LiveExecutionGate.AUTHORIZED, "YES", actor);
        audit.log(actor, "LIVE_AUTHORISED", "live", null, "operator authorised live trading");
    }

    public void revoke(String actor) {
        settings.set(LiveExecutionGate.AUTHORIZED, "NO", actor);
        audit.log(actor, "LIVE_REVOKED", "live", null, "live authorisation revoked");
    }

    public void attestCompliance(String actor, String note) {
        if (note == null || note.trim().length() < 20) throw new IllegalArgumentException("attestation note must state who verified what, and when (>= 20 chars)");
        settings.set(LiveExecutionGate.COMPLIANCE_ATTESTED, "YES", actor);
        audit.log(actor, "LIVE_COMPLIANCE_ATTESTED", "live", null, note.trim());
    }

    /** Read-only capability probe (no orders): funds, a quote and a historical candle request. Success is valid for 24h. */
    public Map<String, Object> probeCapabilities(String actor) {
        Map<String, Object> res = new LinkedHashMap<>();
        boolean allOk = true;
        BrokerAccountProvider acc = account.getIfAvailable();
        MarketDataProvider md = quotes.getIfAvailable();
        HistoricalDataProvider hd = history.getIfAvailable();
        allOk &= probe(res, "funds", () -> { if (acc == null || !"GROWW".equals(acc.sourceName())) throw new IllegalStateException("Groww account provider inactive"); acc.funds(); });
        Optional<Instrument> sample = props.universe().equities().stream().map(s -> instruments.find("NSE", "CASH", s)).flatMap(Optional::stream).findFirst();
        allOk &= probe(res, "quote", () -> md.getQuote(sample.orElseThrow(() -> new IllegalStateException("no persisted equity instrument"))));
        allOk &= probe(res, "historical_candles", () -> {
            Instant now = clock.now();
            hd.getCandles(sample.orElseThrow(), 1, now.minus(Duration.ofDays(2)), now);
        });
        res.put("order_placement_probed", false);
        res.put("note", "probe is read-only; order endpoints are never called during verification");
        if (allOk) settings.set(LiveExecutionGate.CAPABILITIES_VERIFIED_AT, Instant.now().toString(), actor);
        audit.log(actor, "LIVE_CAPABILITY_PROBE", "live", null, allOk ? "all probes ok" : "some probes failed: " + res);
        res.put("verified", allOk);
        return res;
    }

    private boolean probe(Map<String, Object> res, String name, Runnable r) {
        try { r.run(); res.put(name, "OK"); return true; }
        catch (RuntimeException e) { res.put(name, "FAILED: " + com.aiinvestor.util.Redactor.redact(e.getMessage())); return false; }
    }

    // ------------------------------------------------------------------ propose / approve / reject

    public Map<String, Object> propose(ProposeRequest r, String actor) {
        gate.assertAllowed();
        Instrument ins = instruments.findById(r.instrumentId()).orElseThrow(() -> new IllegalArgumentException("unknown instrument"));
        if (!ins.tradable() || ins.type() == InstrumentType.INDEX) throw new IllegalArgumentException(ins.tradingSymbol() + " is not directly tradable");
        if (!"GROWW".equals(ins.source())) throw new IllegalArgumentException("instrument metadata is not from the broker (source " + ins.source() + ")");
        Side side = Side.valueOf(r.side().toUpperCase());
        OrderType type = OrderType.valueOf(r.orderType().toUpperCase());
        if (r.quantity() <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (ins.isOption() && (side != Side.BUY || r.quantity() % ins.lotSize() != 0))
            throw new IllegalArgumentException("options: long (BUY) only and quantity must be a multiple of lot size " + ins.lotSize());
        if (r.stopLoss() == null || r.target() == null) throw new IllegalArgumentException("stop_loss and target are required");
        if (type == OrderType.LIMIT && r.limitPrice() == null) throw new IllegalArgumentException("limit price required for LIMIT");

        MarketDataProvider md = quotes.getObject();
        Quote q = md.getQuote(ins);
        BigDecimal entry = type == OrderType.LIMIT ? r.limitPrice() : BigDecimal.valueOf(side == Side.BUY && q.hasBook() ? q.ask() : q.last());
        Run run = liveRun();
        RunContext ctx = new RunContext(run.id(), RunMode.LIVE, AiMode.RULES_ONLY, run.initialCapital(), props.risk(),
                ExecutionAssumptions.from(props.paper()), props.data().source(), null, false);
        Direction dir = side == Side.BUY ? Direction.LONG : Direction.SHORT;
        RiskDecision rd = risk.evaluate(ctx, new RiskRequest(r.signalId(), ins, dir, side, entry, r.stopLoss(), r.target(), q, false, clock.now(), 1e12, 0, 0));
        if (!rd.approved()) {
            riskEvents.record(run.id(), "LIVE", r.signalId(), "LIVE_PROPOSAL_REJECTED", "RISK_ENGINE", "WARN", rd.rejectionSummary());
            throw new IllegalArgumentException("risk engine rejected the proposal: " + rd.rejectionSummary());
        }
        int qty = Math.min(r.quantity(), rd.quantity());
        BigDecimal value = entry.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_EVEN);
        if (value.compareTo(props.live().maxOrderValue()) > 0) throw new IllegalArgumentException("order value " + value + " exceeds live.max-order-value " + props.live().maxOrderValue());
        String id = Db.newId();
        String reference = "AI" + id.replace("-", "").substring(0, 14).toUpperCase();
        Instant now = clock.now();
        jdbc.sql("""
                INSERT INTO live_order_approvals (id, run_id, signal_id, instrument_id, side, order_type, quantity, limit_price, est_order_value,
                  reference, risk_json, status, proposed_by, proposed_at, expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
                .params(id, run.id(), r.signalId(), ins.id(), side.name(), type.name(), qty, type == OrderType.LIMIT ? r.limitPrice() : null, value,
                        reference, json(Map.of("checks", rd.checks(), "stop", r.stopLoss(), "target", r.target())), "PENDING_APPROVAL", actor,
                        Db.ts(now), Db.ts(now.plus(Duration.ofMinutes(props.live().approvalTtlMinutes()))))
                .update();
        audit.log(actor, "LIVE_ORDER_PROPOSED", "live_order", id, ins.tradingSymbol() + " " + side + " x" + qty + " value " + value);
        return get(id);
    }

    public Map<String, Object> reject(String id, String actor, String note) {
        int n = jdbc.sql("UPDATE live_order_approvals SET status='REJECTED', decided_by=?, decided_at=?, note=? WHERE id=? AND status='PENDING_APPROVAL'")
                .params(actor, Db.ts(Instant.now()), note, id).update();
        if (n == 0) throw new IllegalStateException("not pending");
        audit.log(actor, "LIVE_ORDER_REJECTED", "live_order", id, note);
        return get(id);
    }

    /** Human approval → submission. Fails closed on any gate/funds/param problem. Never retries. */
    public Map<String, Object> approve(String id, String actor) {
        Map<String, Object> row = get(id);
        if (!"PENDING_APPROVAL".equals(row.get("status"))) throw new IllegalStateException("order is " + row.get("status") + ", not pending approval");
        Instant now = clock.now();
        if (Db.toInstant(row.get("expires_at")).isBefore(now)) {
            setStatus(id, "EXPIRED", actor, "approval window elapsed");
            throw new IllegalStateException("approval window elapsed");
        }
        gate.assertAllowed();
        Instrument ins = instruments.findById(((Number) row.get("instrument_id")).longValue()).orElseThrow();
        BrokerOrderProvider broker = orders.getIfAvailable();
        if (broker == null) throw new LiveExecutionGate.LiveDisabledException("no broker order provider is configured");
        // re-run the independent risk engine on a FRESH quote: limits, kill switch, data freshness and session apply at approval time too
        reevaluateRisk(id, row, ins, actor);
        BigDecimal value = (BigDecimal) row.get("est_order_value");
        BrokerAccountProvider acc = account.getObject();
        AccountFunds funds = acc.funds();
        BigDecimal needed = value.multiply(new BigDecimal("1.02"));
        if (funds.availableCash() == null || funds.availableCash().compareTo(needed) < 0) {
            setStatus(id, "FAILED", actor, "insufficient available funds (" + funds.availableCash() + " < " + needed.setScale(2, RoundingMode.HALF_EVEN) + ")");
            throw new IllegalStateException("insufficient available funds");
        }
        String reference = (String) row.get("reference");
        String segment = ins.segment();
        try {
            // idempotency guard: if an earlier ambiguous attempt actually reached the broker, adopt it instead of re-sending
            Optional<BrokerOrder> existing = broker.statusByReference(reference, segment);
            if (existing.isPresent()) return adopt(id, row, ins, existing.get(), actor);
        } catch (BrokerException e) {
            setStatus(id, "UNKNOWN_RECONCILE", actor, "could not verify reference before submit: " + e.getClass().getSimpleName());
            throw new IllegalStateException("cannot verify broker state; order NOT sent. Use reconcile.");
        }
        BrokerOrderRequest req = new BrokerOrderRequest(reference, ins.exchange(), segment, ins.tradingSymbol(), (String) row.get("side"),
                (String) row.get("order_type"), "MIS", "DAY", ((Number) row.get("quantity")).intValue(), (BigDecimal) row.get("limit_price"), null);
        audit.log(actor, "LIVE_ORDER_SUBMITTING", "live_order", id, ins.tradingSymbol() + " ref " + reference);
        try {
            BrokerOrderAck ack = broker.place(req);
            String localId = persistLocal(id, row, ins, ack.brokerOrderId(), ack.status(), OrderStatus.PENDING, 0, null);
            jdbc.sql("UPDATE live_order_approvals SET status='SUBMITTED', decided_by=?, decided_at=?, broker_order_id=?, local_order_id=? WHERE id=?")
                    .params(actor, Db.ts(Instant.now()), ack.brokerOrderId(), localId, id).update();
            audit.log(actor, "LIVE_ORDER_SUBMITTED", "live_order", id, "broker order " + ack.brokerOrderId());
        } catch (BrokerException.Ambiguous e) {
            setStatus(id, "UNKNOWN_RECONCILE", actor, "submission outcome unknown (" + e.getMessage() + "); NOT retried — reconcile by reference");
            audit.log(actor, "LIVE_ORDER_AMBIGUOUS", "live_order", id, "reconcile required");
        } catch (BrokerException.Rejected | BrokerException.Auth | BrokerException.RateLimited | BrokerException.NotEntitled e) {
            setStatus(id, "FAILED", actor, e.getClass().getSimpleName() + ": " + e.getMessage());
            audit.log(actor, "LIVE_ORDER_FAILED", "live_order", id, e.getClass().getSimpleName());
        }
        return get(id);
    }

    private void reevaluateRisk(String id, Map<String, Object> row, Instrument ins, String actor) {
        try {
            com.fasterxml.jackson.databind.JsonNode j = mapper.readTree((String) row.get("risk_json"));
            BigDecimal stop = new BigDecimal(j.get("stop").asText()), target = new BigDecimal(j.get("target").asText());
            Side side = Side.valueOf((String) row.get("side"));
            Quote q = quotes.getObject().getQuote(ins);
            BigDecimal limit = (BigDecimal) row.get("limit_price");
            BigDecimal entry = limit != null ? limit : BigDecimal.valueOf(side == Side.BUY && q.hasBook() ? q.ask() : q.last());
            Run run = liveRun();
            RunContext ctx = new RunContext(run.id(), RunMode.LIVE, AiMode.RULES_ONLY, run.initialCapital(), props.risk(),
                    ExecutionAssumptions.from(props.paper()), props.data().source(), null, false);
            Direction dir = side == Side.BUY ? Direction.LONG : Direction.SHORT;
            RiskDecision rd = risk.evaluate(ctx, new RiskRequest((String) row.get("signal_id"), ins, dir, side, entry, stop, target, q, false, clock.now(), 1e12, 0, 0));
            if (!rd.approved()) {
                setStatus(id, "FAILED", actor, "risk engine rejected at approval time: " + rd.rejectionSummary());
                riskEvents.record(run.id(), "LIVE", (String) row.get("signal_id"), "LIVE_APPROVAL_RISK_REJECTED", "RISK_ENGINE", "WARN", rd.rejectionSummary());
                throw new IllegalStateException("risk engine rejected the order at approval time: " + rd.rejectionSummary());
            }
        } catch (JsonProcessingException e) {
            setStatus(id, "FAILED", actor, "stored risk context unreadable");
            throw new IllegalStateException("stored risk context unreadable; order NOT sent");
        }
    }

    /** Resolve an UNKNOWN_RECONCILE / SUBMITTED order against the broker (by reference). */
    public Map<String, Object> reconcile(String id, String actor) {
        Map<String, Object> row = get(id);
        String status = (String) row.get("status");
        if (!List.of("UNKNOWN_RECONCILE", "SUBMITTED").contains(status)) throw new IllegalStateException("nothing to reconcile for status " + status);
        BrokerOrderProvider broker = orders.getObject();
        Instrument ins = instruments.findById(((Number) row.get("instrument_id")).longValue()).orElseThrow();
        Optional<BrokerOrder> bo = broker.statusByReference((String) row.get("reference"), ins.segment());
        if (bo.isPresent()) {
            if ("UNKNOWN_RECONCILE".equals(status)) return adopt(id, row, ins, bo.get(), actor);
            syncLocal((String) row.get("local_order_id"), bo.get());
        } else if ("UNKNOWN_RECONCILE".equals(status)) {
            Instant decided = row.get("decided_at") == null ? Instant.EPOCH : Db.toInstant(row.get("decided_at"));
            if (Duration.between(decided, Instant.now()).compareTo(Duration.ofMinutes(2)) < 0)
                throw new IllegalStateException("broker has no record yet; wait at least 2 minutes before concluding the order was not received");
            setStatus(id, "FAILED", actor, "no order with this reference exists at the broker; safe to propose again");
        }
        audit.log(actor, "LIVE_ORDER_RECONCILED", "live_order", id, bo.map(BrokerOrder::status).orElse("not found"));
        return get(id);
    }

    private Map<String, Object> adopt(String id, Map<String, Object> row, Instrument ins, BrokerOrder bo, String actor) {
        String localId = persistLocal(id, row, ins, bo.brokerOrderId(), bo.status(), mapStatus(bo), bo.filledQuantity(), bo.avgFillPrice());
        jdbc.sql("UPDATE live_order_approvals SET status='SUBMITTED', decided_by=?, decided_at=?, broker_order_id=?, local_order_id=? WHERE id=?")
                .params(actor, Db.ts(Instant.now()), bo.brokerOrderId(), localId, id).update();
        audit.log(actor, "LIVE_ORDER_ADOPTED", "live_order", id, "broker order " + bo.brokerOrderId() + " already existed for reference; not re-sent");
        return get(id);
    }

    private String persistLocal(String approvalId, Map<String, Object> row, Instrument ins, String brokerId, String brokerStatus, OrderStatus st, int filled, BigDecimal avg) {
        String localId = Db.newId();
        OrderRec rec = new OrderRec(localId, (String) row.get("run_id"), RunMode.LIVE, (String) row.get("reference"), (String) row.get("signal_id"), null,
                ins.id(), Side.valueOf((String) row.get("side")), OrderType.valueOf((String) row.get("order_type")), OrderPurpose.ENTRY,
                ((Number) row.get("quantity")).intValue(), (BigDecimal) row.get("limit_price"), OrderStatus.PENDING, 0, null, Instant.now(), null, null, null, "LIVE_MANUAL");
        trades.insertOrder(rec, false);
        trades.syncBroker(localId, brokerId, brokerStatus, st, filled, avg, Instant.now());
        return localId;
    }

    private void syncLocal(String localId, BrokerOrder bo) {
        if (localId != null) trades.syncBroker(localId, bo.brokerOrderId(), bo.status(), mapStatus(bo), bo.filledQuantity(), bo.avgFillPrice(), Instant.now());
    }

    private static OrderStatus mapStatus(BrokerOrder bo) {
        String s = bo.status() == null ? "" : bo.status().toUpperCase();
        if (s.contains("EXECUTED") || s.contains("COMPLETE") || s.contains("FILLED")) return OrderStatus.FILLED;
        if (s.contains("CANCEL")) return OrderStatus.CANCELLED;
        if (s.contains("REJECT") || s.contains("FAIL")) return OrderStatus.REJECTED;
        return bo.filledQuantity() > 0 ? OrderStatus.PARTIAL : OrderStatus.PENDING;
    }

    private Run liveRun() {
        return runs.list(RunMode.LIVE).stream().findFirst()
                .flatMap(m -> runs.find((String) m.get("id")))
                .orElseGet(() -> runs.create(RunMode.LIVE, "live-default", AiMode.RULES_ONLY, props.data().source(),
                        props.paper().initialCapital(), null, null, null, null));
    }

    private void setStatus(String id, String status, String actor, String note) {
        jdbc.sql("UPDATE live_order_approvals SET status=?, decided_by=?, decided_at=?, note=? WHERE id=?")
                .params(status, actor, Db.ts(Instant.now()), note == null ? null : note.substring(0, Math.min(500, note.length())), id).update();
    }

    public Map<String, Object> get(String id) {
        return jdbc.sql("""
                SELECT a.*, i.trading_symbol AS instrument_symbol FROM live_order_approvals a JOIN instruments i ON i.id=a.instrument_id WHERE a.id=?""")
                .param(id).query().listOfRows().stream().findFirst().orElseThrow(() -> new IllegalArgumentException("unknown approval"));
    }

    public List<Map<String, Object>> list() {
        return jdbc.sql("""
                SELECT a.*, i.trading_symbol AS instrument_symbol FROM live_order_approvals a JOIN instruments i ON i.id=a.instrument_id
                ORDER BY a.proposed_at DESC LIMIT 100""").query().listOfRows();
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (JsonProcessingException e) { return "{}"; }
    }
}
