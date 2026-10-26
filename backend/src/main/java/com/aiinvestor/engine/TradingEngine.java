package com.aiinvestor.engine;

import com.aiinvestor.ai.AiAnalysisService;
import com.aiinvestor.ai.AnalysisOutcome;
import com.aiinvestor.ai.AnalysisRequest;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Enums.*;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Money;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.domain.TimeFrame;
import com.aiinvestor.engine.SnapshotBuilder.RiskBudget;
import com.aiinvestor.indicators.IndicatorSet;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.paper.PaperBroker;
import com.aiinvestor.persistence.*;
import com.aiinvestor.persistence.DailyStateRepository.DayState;
import com.aiinvestor.persistence.SignalRepository.NewSignal;
import com.aiinvestor.persistence.TradeRepository.OrderRec;
import com.aiinvestor.persistence.TradeRepository.PositionRec;
import com.aiinvestor.risk.RiskEngine;
import com.aiinvestor.risk.RiskModels.CheckResult;
import com.aiinvestor.risk.RiskModels.RiskDecision;
import com.aiinvestor.risk.RiskModels.RiskRequest;
import com.aiinvestor.strategy.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One evaluation step at time {@code view.asOf()}: (1) simulate execution of pending orders/positions on the bars that
 * completed since the last step, (2) force-flat at square-off time, (3) evaluate strategies on completed candles,
 * (4) for candidates run the optional AI stage and the independent risk engine, (5) place simulated entry orders.
 * The same code serves paper-live, replay and backtests; it never talks to a real broker.
 */
@Service
public class TradingEngine {
    private static final Logger log = LoggerFactory.getLogger(TradingEngine.class);

    public record StepResult(int evaluated, int candidates, int approved, int rejected) {}

    private final StrategyRegistry strategies;
    private final MarketSession session;
    private final PaperBroker broker;
    private final RiskEngine risk;
    private final AiAnalysisService ai;
    private final SignalRepository signals;
    private final RiskEventRepository riskEvents;
    private final TradeRepository trades;
    private final DailyStateRepository daily;
    private final InstrumentRepository instrumentRepo;
    private final OptionPlanner optionPlanner;
    private final SnapshotBuilder snapshotBuilder;
    private final AppProperties props;
    private final ObjectMapper mapper;

    private final Map<String, Instant> lastProcessed = new ConcurrentHashMap<>();
    private final Map<Long, Instrument> instrumentCache = new ConcurrentHashMap<>();

    public TradingEngine(StrategyRegistry strategies, MarketSession session, PaperBroker broker, RiskEngine risk,
                         AiAnalysisService ai, SignalRepository signals, RiskEventRepository riskEvents, TradeRepository trades,
                         DailyStateRepository daily, InstrumentRepository instrumentRepo, OptionPlanner optionPlanner,
                         SnapshotBuilder snapshotBuilder, AppProperties props, ObjectMapper mapper) {
        this.strategies = strategies;
        this.session = session;
        this.broker = broker;
        this.risk = risk;
        this.ai = ai;
        this.signals = signals;
        this.riskEvents = riskEvents;
        this.trades = trades;
        this.daily = daily;
        this.instrumentRepo = instrumentRepo;
        this.optionPlanner = optionPlanner;
        this.snapshotBuilder = snapshotBuilder;
        this.props = props;
        this.mapper = mapper;
    }

    /** Drops in-memory bookkeeping for a finished run. */
    public void forgetRun(String runId) {
        lastProcessed.keySet().removeIf(k -> k.startsWith(runId + "|"));
    }

    /** Between strategy boundaries: only advance simulated fills/exits (and forced square-off). */
    public void executionTick(RunContext run, MarketView view) {
        if (run.mode() == RunMode.LIVE) throw new IllegalStateException("TradingEngine only runs simulated modes");
        Instant now = view.asOf();
        processExecutions(run, view, now);
        if (session.isSquareOffTime(now)) squareOffAll(run, view, now);
    }

    public StepResult step(RunContext run, MarketView view, List<Instrument> universe, int aiCallBudget) {
        if (run.mode() == RunMode.LIVE) throw new IllegalStateException("TradingEngine only runs simulated modes; live orders use LiveOrderService");
        Instant now = view.asOf();
        processExecutions(run, view, now);
        if (session.isSquareOffTime(now)) {
            squareOffAll(run, view, now);
            return new StepResult(0, 0, 0, 0);
        }
        if (!session.allowsNewEntries(now)) return new StepResult(0, 0, 0, 0);

        Set<String> held = heldUnderlyings(run);
        int evaluated = 0, candidates = 0, approved = 0, rejected = 0;
        for (Instrument u : universe) {
            if (held.contains(u.tradingSymbol())) continue;       // one live idea per underlying
            int[] r = evaluateInstrument(run, view, u, aiCallBudget);
            evaluated += r[0]; candidates += r[1]; approved += r[2]; rejected += r[3];
        }
        return new StepResult(evaluated, candidates, approved, rejected);
    }

    // ------------------------------------------------------------------ execution simulation

    private void processExecutions(RunContext run, MarketView view, Instant now) {
        Map<Long, Instant> earliest = new LinkedHashMap<>();
        for (PositionRec p : trades.openPositions(run.runId())) earliest.merge(p.instrumentId(), p.openedAt(), (a, b) -> a.isBefore(b) ? a : b);
        for (OrderRec o : trades.openOrders(run.runId())) earliest.merge(o.instrumentId(), o.createdAt(), (a, b) -> a.isBefore(b) ? a : b);
        for (Map.Entry<Long, Instant> e : earliest.entrySet()) {
            Instrument inst = instrument(e.getKey());
            String key = run.runId() + "|" + e.getKey();
            Instant from = lastProcessed.getOrDefault(key, e.getValue());
            List<Candle> bars = view.bars1m(inst, from, now);
            broker.processBars(run, inst, bars);
            lastProcessed.put(key, now);
        }
    }

    private void squareOffAll(RunContext run, MarketView view, Instant now) {
        broker.cancelPendingEntries(run, "square-off time reached", now);
        for (PositionRec p : trades.openPositions(run.runId())) {
            Instrument inst = instrument(p.instrumentId());
            List<Candle> recent = view.bars1m(inst, now.minus(Duration.ofMinutes(30)), now);
            double px = recent.isEmpty() ? p.avgEntry().doubleValue() : recent.get(recent.size() - 1).close();
            broker.squareOff(run, inst, px, now, "EOD_SQUARE_OFF");
        }
    }

    private Set<String> heldUnderlyings(RunContext run) {
        Set<String> out = new HashSet<>();
        for (PositionRec p : trades.openPositions(run.runId())) out.add(underlyingOf(instrument(p.instrumentId())));
        for (OrderRec o : trades.openOrders(run.runId())) {
            if (o.purpose() == OrderPurpose.ENTRY) out.add(underlyingOf(instrument(o.instrumentId())));
        }
        return out;
    }

    private static String underlyingOf(Instrument i) { return i.underlying() != null ? i.underlying() : i.tradingSymbol(); }

    private Instrument instrument(long id) {
        return instrumentCache.computeIfAbsent(id, k -> instrumentRepo.findById(k).orElseThrow());
    }

    // ------------------------------------------------------------------ strategy evaluation

    private int[] evaluateInstrument(RunContext run, MarketView view, Instrument u, int aiBudget) {
        Instant now = view.asOf();
        TimeFrame tf = new TimeFrame(props.data().timeframeMinutes());
        List<Candle> candles = view.candles(u, tf.minutes());
        LocalDate day = session.dateOf(now);
        int todayStart = 0;
        while (todayStart < candles.size() && !session.dateOf(candles.get(todayStart).start()).equals(day)) todayStart++;

        String dq = dataQualityProblem(candles, todayStart, tf, now, day);
        if (dq != null) {
            Candle last = candles.isEmpty() ? null : candles.get(candles.size() - 1);
            Instant barEnd = last == null ? now : last.end(tf.minutes());
            insertSimple(run, u, "DATA_GATE", "1", tf, barEnd, Decision.INSUFFICIENT_DATA, List.of(), List.of(dq), null, FinalStatus.NO_TRADE, dq);
            return new int[]{1, 0, 0, 0};
        }
        Optional<Quote> q = view.quote(u);
        CandleContext ctx = new CandleContext(u, tf, now, candles, todayStart, session, q.orElse(null));
        IndicatorSet ind = IndicatorSet.compute(ctx);
        int evaluated = 0, cands = 0, appr = 0, rej = 0;
        for (Strategy s : strategies.all()) {
            Params p = paramsFor(run, s);
            if (!p.b("enabled", true)) continue;
            StrategyResult res = s.evaluate(ctx, ind, p);
            evaluated++;
            String key = run.runId() + ":" + s.name() + ":" + u.id() + ":" + ctx.lastBarEnd().toEpochMilli();
            if (signals.exists(run.runId(), key)) continue;
            if (!res.isCandidate()) {
                insertRules(run, u, s, tf, ctx, ind, res, key, null, null, FinalStatus.NO_TRADE, firstOrNull(res.contradicting()));
                continue;
            }
            cands++;
            boolean ok = handleCandidate(run, view, u, ctx, ind, s, res, key, aiBudget);
            if (ok) appr++; else rej++;
        }
        return new int[]{evaluated, cands, appr, rej};
    }

    /** Effective parameters of every strategy for this run, recorded as immutable snapshots; returns name → stored version label. */
    public Map<String, Object> recordStrategyConfigs(RunContext run, StrategyConfigRepository repo) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Strategy s : strategies.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            Params p = paramsFor(run, s);
            m.put("version", repo.record(s.name(), s.version(), toJson(new TreeMap<>(p.values()))));
            m.put("parameters", new TreeMap<>(p.values()));
            out.put(s.name(), m);
        }
        return out;
    }

    private Params paramsFor(RunContext run, Strategy s) {
        Params p = s.defaultParams();
        Map<String, Object> cfg = props.strategies() == null ? null : props.strategies().get(s.name());
        if (cfg != null) p = p.merge(new Params(cfg));
        return p.merge(run.overridesFor(s.name()));
    }

    private String dataQualityProblem(List<Candle> c, int todayStart, TimeFrame tf, Instant now, LocalDate day) {
        AppProperties.Data d = props.data();
        if (c.isEmpty()) return "MISSING: no candles";
        if (c.size() < d.minBarsRequired()) return "INCOMPLETE: " + c.size() + " bars < " + d.minBarsRequired() + " required";
        if (todayStart >= c.size()) return "INCOMPLETE: no completed bars yet today";
        Candle last = c.get(c.size() - 1);
        long lag = Duration.between(last.end(tf.minutes()), now).getSeconds();
        if (lag > 2L * tf.minutes() * 60) return "STALE: last completed bar ended " + lag + "s ago";
        for (Candle k : c.subList(Math.max(0, c.size() - 50), c.size())) if (!k.isConsistent()) return "INCONSISTENT: invalid OHLC at " + k.start();
        long expected = Duration.between(session.openInstant(day), last.end(tf.minutes())).toMinutes() / tf.minutes();
        long missing = expected - (c.size() - todayStart);
        if (missing > d.maxMissingBars()) return "INCOMPLETE: " + missing + " bars missing today (max " + d.maxMissingBars() + ")";
        return null;
    }

    // ------------------------------------------------------------------ candidate handling

    private boolean handleCandidate(RunContext run, MarketView view, Instrument u, CandleContext ctx, IndicatorSet ind,
                                    Strategy s, StrategyResult res, String key, int aiBudget) {
        Instant now = view.asOf();
        boolean isBuy = res.decision() == Decision.BUY_CANDIDATE;
        TimeFrame tf = ctx.timeframe();
        Instrument tradable = u;
        BigDecimal entry, stop, target;
        Quote quote;
        double premiumMove = 0;
        Map<String, Object> optionDetails = null;
        String planRejection = null;
        BigDecimal tick = u.tickSize();
        if (u.isIndex()) {
            OptionPlanner.Result pr = optionPlanner.plan(run, u, res.decision(), ind.close(), view, tf.minutes());
            if (pr.plan() == null) {
                planRejection = "no tradable option plan: " + pr.rejection();
                entry = stop = target = null;
                quote = null;
            } else {
                OptionPlanner.Plan plan = pr.plan();
                tradable = plan.contract();
                entry = plan.entry(); stop = plan.stop(); target = plan.target();
                quote = plan.quote();
                premiumMove = plan.recentPremiumMove();
                optionDetails = plan.details();
            }
        } else {
            entry = Money.roundToTick(BigDecimal.valueOf(res.entry()), tick);
            stop = Money.roundToTick(BigDecimal.valueOf(res.stop()), tick);
            target = Money.roundToTick(BigDecimal.valueOf(res.target()), tick);
            quote = view.quote(u).orElse(null);
        }
        double avgBarVol = Double.isNaN(ind.avgVolume20()) ? 0 : ind.avgVolume20();
        RiskBudget budget = budget(run, now);
        Quote underlyingQuote = u.isIndex() ? null : quote;      // an index has no order book; the option's book is in option_plan
        Map<String, Object> snapshot = snapshotBuilder.build(run, ctx, ind, s, res, underlyingQuote, view.syntheticQuotes(), optionDetails, null,
                budget, avgBarVol, view.dataSource());
        String signalId = Db.newId();

        if (planRejection != null) {
            insertCandidate(run, u, s, tf, ctx, ind, res, key, signalId, snapshot, null, null, null, null, null, FinalStatus.REJECTED, planRejection, tradable);
            riskEvents.record(run.runId(), run.mode().name(), signalId, "OPTION_PLAN_REJECTED", "OPTION_PLANNER", "INFO", planRejection);
            return false;
        }

        // ---- AI stage
        AnalysisOutcome outcome = null;
        Boolean agrees = null;
        String disagreement = null;
        if (run.aiMode() != AiMode.RULES_ONLY && ai.enabled()) {
            AnalysisRequest areq = new AnalysisRequest(run.runId(), run.mode().name(), signalId, u.tradingSymbol(), s.name(), s.version(),
                    tf.label(), res.decision(), ind.close(), ind.atr14(), snapshot);
            outcome = ai.analyze(areq, run.mode() == RunMode.BACKTEST, aiBudget);
            agrees = outcome.isValid() ? AiAnalysisService.agrees(res.decision(), outcome.assessment()) : null;
            disagreement = AiAnalysisService.disagreementNote(res.decision(), outcome);
        }
        if (run.aiMode() == AiMode.AI_GATED && ai.enabled() && (outcome == null || !outcome.isValid() || !Boolean.TRUE.equals(agrees))) {
            String why = outcome == null ? "AI analysis unavailable" : !outcome.isValid()
                    ? "AI analysis not usable (" + outcome.status() + (outcome.errors().isEmpty() ? "" : ": " + outcome.errors().get(0)) + ")"
                    : "AI did not confirm the setup: " + disagreement;
            insertCandidate(run, u, s, tf, ctx, ind, res, key, signalId, snapshot, outcome, agrees, disagreement, null, null, FinalStatus.REJECTED, why, tradable);
            return false;
        }

        // ---- independent risk stage
        Direction dir = (u.isIndex() || isBuy) ? Direction.LONG : Direction.SHORT;
        RiskRequest rr = new RiskRequest(signalId, tradable, dir, dir == Direction.LONG ? Side.BUY : Side.SELL, entry, stop, target,
                quote, view.syntheticQuotes(), now, avgBarVol, premiumMove, 0);
        RiskDecision rd = risk.evaluate(run, rr);
        String riskJson = toJson(rd.checks());
        if (!rd.approved()) {
            for (CheckResult c : rd.failures()) riskEvents.record(run.runId(), run.mode().name(), signalId, "CHECK_FAILED", c.name(), "WARN", c.detail());
            insertCandidate(run, u, s, tf, ctx, ind, res, key, signalId, snapshot, outcome, agrees, disagreement, "REJECTED", riskJson,
                    FinalStatus.REJECTED, "risk engine: " + rd.rejectionSummary(), tradable);
            return false;
        }
        insertCandidate(run, u, s, tf, ctx, ind, res, key, signalId, snapshot, outcome, agrees, disagreement, "APPROVED", riskJson,
                FinalStatus.APPROVED, null, tradable);
        Optional<OrderRec> order = broker.submitEntry(run, new PaperBroker.EntryRequest(signalId, s.name(), tradable, dir, rd.quantity(),
                entry, stop, target, now));
        if (order.isEmpty()) {
            signals.finalize(signalId, FinalStatus.REJECTED, "duplicate order (client order id already exists)", null, null);
            riskEvents.record(run.runId(), run.mode().name(), signalId, "DUPLICATE_ORDER", "DUPLICATE_ORDER", "WARN", "duplicate entry order suppressed");
            return false;
        }
        signals.finalize(signalId, FinalStatus.APPROVED, null, order.get().id(), null);
        return true;
    }

    private RiskBudget budget(RunContext run, Instant now) {
        DayState ds = daily.get(run.runId(), session.dateOf(now));
        AppProperties.Global g = run.risk().global();
        double room = Math.max(0, g.maxDailyLoss().add(ds.realizedNet()).doubleValue());
        int open = trades.openPositions(run.runId()).size();
        return new RiskBudget(room, Math.max(0, g.maxTradesPerDay() - ds.trades() - open), open,
                Math.min(room, run.risk().equity().maxRiskPerTrade().doubleValue()));
    }

    // ------------------------------------------------------------------ persistence helpers

    private void insertSimple(RunContext run, Instrument u, String strategy, String version, TimeFrame tf, Instant barEnd, Decision d,
                              List<String> sup, List<String> con, String ind, FinalStatus fs, String reason) {
        String key = run.runId() + ":" + strategy + ":" + u.id() + ":" + barEnd.toEpochMilli();
        if (signals.exists(run.runId(), key)) return;
        signals.insert(new NewSignal(null, run.runId(), run.mode().name(), key, barEnd, u.id(), u.underlying(), strategy, version, tf.label(),
                d, toJson(Map.of("supporting", sup, "contradicting", con)), null, null, null, null, null, ind, null, null, fs, reason));
    }

    private void insertRules(RunContext run, Instrument u, Strategy s, TimeFrame tf, CandleContext ctx, IndicatorSet ind,
                             StrategyResult res, String key, String snapshot, String hash, FinalStatus fs, String reason) {
        signals.insert(new NewSignal(null, run.runId(), run.mode().name(), key, ctx.lastBarEnd(), u.id(), u.underlying(), s.name(), s.version(),
                tf.label(), res.decision(), toJson(Map.of("supporting", res.supporting(), "contradicting", res.contradicting(),
                "invalidation", res.invalidation())), null, null, null, null, ind.regime(), toJson(ind.toMap()), snapshot, hash, fs, reason));
    }

    private void insertCandidate(RunContext run, Instrument u, Strategy s, TimeFrame tf, CandleContext ctx, IndicatorSet ind,
                                 StrategyResult res, String key, String signalId, Map<String, Object> snapshot, AnalysisOutcome outcome,
                                 Boolean agrees, String disagreement, String riskDecision, String riskJson, FinalStatus fs,
                                 String reason, Instrument tradable) {
        String snap = toJson(snapshot);
        BigDecimal rrv = BigDecimal.valueOf(Math.round(res.riskReward() * 10_000) / 10_000.0);
        signals.insert(new NewSignal(signalId, run.runId(), run.mode().name(), key, ctx.lastBarEnd(), u.id(), u.underlying(), s.name(),
                s.version(), tf.label(), res.decision(),
                toJson(Map.of("supporting", res.supporting(), "contradicting", res.contradicting(), "invalidation", res.invalidation())),
                Money.price4(BigDecimal.valueOf(res.entry())), Money.price4(BigDecimal.valueOf(res.stop())),
                Money.price4(BigDecimal.valueOf(res.target())), rrv, ind.regime(), toJson(ind.toMap()), snap, sha(snap), fs, reason));
        if (tradable != null && !tradable.id().equals(u.id())) signals.recordTradedInstrument(signalId, tradable.id());
        if (outcome != null) {
            signals.recordAi(signalId, outcome.recordId(), outcome.isValid() ? outcome.assessment().decision().name() : outcome.status().name(), agrees, disagreement);
        }
        if (riskDecision != null) signals.recordRisk(signalId, riskDecision, riskJson);
    }

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); } catch (JsonProcessingException e) { return "{}"; }
    }

    private static String firstOrNull(List<String> l) { return l == null || l.isEmpty() ? null : l.get(0); }

    private static String sha(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------ end of day

    /**
     * Fills in the "subsequent price movement" and hypothetical outcome of every candidate signal of {@code day}
     * (traded or not) by walking the strategy's own levels forward over the rest of the session.
     */
    public int finalizeDay(RunContext run, MarketView view, LocalDate day) {
        int n = 0;
        Instant close = session.squareOffInstant(day);
        for (Map<String, Object> row : signals.forRun(run.runId())) {
            Object dec = row.get("rule_decision");
            if (!"BUY_CANDIDATE".equals(dec) && !"SELL_CANDIDATE".equals(dec)) continue;
            if (row.get("subsequent_move_json") != null) continue;
            Instant barEnd = Db.toInstant(row.get("bar_time"));
            if (!session.dateOf(barEnd).equals(day)) continue;
            Instrument u = instrument(((Number) row.get("instrument_id")).longValue());
            List<Candle> bars = view.bars1m(u, barEnd, close);
            Direction dir = "BUY_CANDIDATE".equals(dec) ? Direction.LONG : Direction.SHORT;
            OutcomeTracker.Outcome o = OutcomeTracker.simulate(dir, ((Number) row.get("proposed_entry")).doubleValue(),
                    ((Number) row.get("proposed_stop")).doubleValue(), ((Number) row.get("proposed_target")).doubleValue(), bars);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("basis", "strategy levels on analysed instrument (underlying for index signals); cost-free; R multiples");
            m.put("hypothetical", o.toMap());
            signals.recordSubsequentMove((String) row.get("id"), toJson(m));
            n++;
        }
        return n;
    }
}
