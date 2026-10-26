package com.aiinvestor.runtime;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Enums.AiMode;
import com.aiinvestor.domain.Enums.RunMode;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.engine.ExecutionAssumptions;
import com.aiinvestor.engine.LiveMarketView;
import com.aiinvestor.engine.RunContext;
import com.aiinvestor.engine.TradingEngine;
import com.aiinvestor.engine.UniverseService;
import com.aiinvestor.market.HistoricalDataProvider;
import com.aiinvestor.market.MarketDataProvider;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.market.SettableClock;
import com.aiinvestor.market.TradingClock;
import com.aiinvestor.persistence.AuditRepository;
import com.aiinvestor.persistence.InstrumentRepository;
import com.aiinvestor.persistence.RunRepository;
import com.aiinvestor.persistence.RunRepository.Run;
import com.aiinvestor.persistence.TradeRepository;
import com.aiinvestor.persistence.TradeRepository.PositionRec;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives the long-lived PAPER run: restart recovery, periodic execution ticks, strategy evaluation at every completed
 * candle boundary, end-of-day finalisation and reconciliation. Uses real provider data (Groww when configured) or the
 * mock provider; all orders are simulated. In MOCK replay mode a settable clock steps through synthetic trading days.
 */
@Service
public class PaperTradingService {
    private static final Logger log = LoggerFactory.getLogger(PaperTradingService.class);

    private final AppProperties props;
    private final RunRepository runs;
    private final TradingClock clock;
    private final MarketSession session;
    private final TradingEngine engine;
    private final UniverseService universe;
    private final HistoricalDataProvider history;
    private final MarketDataProvider quotes;
    private final TransientStore store;
    private final ReconciliationService reconciliation;
    private final AuditRepository audit;
    private final TradeRepository trades;
    private final InstrumentRepository instrumentRepo;
    private final org.springframework.beans.factory.ObjectProvider<com.aiinvestor.api.EventBroadcaster> events;
    private final com.aiinvestor.persistence.StrategyConfigRepository strategyConfigs;
    private final com.aiinvestor.persistence.CandleRepository candleArchive;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    private volatile Run run;
    private volatile RunContext ctx;
    private volatile LiveMarketView view;
    private volatile List<Instrument> uni = List.of();
    private volatile Instant lastEvaluatedBoundary = Instant.EPOCH;
    private volatile LocalDate lastActiveDay;
    private volatile LocalDate lastFinalizedDay;
    private volatile long lastActionMs;
    private volatile Instant lastTickAt;
    private volatile String lastError;
    private volatile boolean stopping;
    private final Map<String, Double> marks = new ConcurrentHashMap<>();

    public PaperTradingService(AppProperties props, RunRepository runs, TradingClock clock, MarketSession session, TradingEngine engine,
                               UniverseService universe, HistoricalDataProvider history, MarketDataProvider quotes, TransientStore store,
                               ReconciliationService reconciliation, AuditRepository audit, TradeRepository trades, InstrumentRepository instrumentRepo,
                               org.springframework.beans.factory.ObjectProvider<com.aiinvestor.api.EventBroadcaster> events,
                               com.aiinvestor.persistence.StrategyConfigRepository strategyConfigs, com.aiinvestor.persistence.CandleRepository candleArchive,
                               com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.events = events;
        this.strategyConfigs = strategyConfigs;
        this.candleArchive = candleArchive;
        this.mapper = mapper;
        this.props = props;
        this.runs = runs;
        this.clock = clock;
        this.session = session;
        this.engine = engine;
        this.universe = universe;
        this.history = history;
        this.quotes = quotes;
        this.store = store;
        this.reconciliation = reconciliation;
        this.audit = audit;
        this.trades = trades;
        this.instrumentRepo = instrumentRepo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        AiMode mode = AiMode.valueOf(props.paper().aiMode().toUpperCase());
        this.run = runs.getOrCreatePaperRun(props.paper().runName(), mode, props.data().source(), props.paper().initialCapital());
        this.ctx = buildContext(run);
        this.view = new LiveMarketView(history, quotes, session, clock, props.data().warmupDays(), props.data().source());
        recordRunConfig();
        try { this.uni = universe.analysisUniverse(); } catch (RuntimeException e) { lastError = "universe: " + e.getMessage(); log.warn("could not resolve universe: {}", e.getMessage()); }
        int openPos = trades.openPositions(run.id()).size(), openOrd = trades.openOrders(run.id()).size();
        audit.log("system", "PAPER_RUN_STARTED", "run", run.id(), "mode=" + run.aiMode() + " data=" + run.dataSource() + " recovered open positions=" + openPos + " pending orders=" + openOrd);
        log.info("Paper run {} ready (aiMode={}, data={}, recovered {} open positions, {} pending orders)", run.id(), run.aiMode(), run.dataSource(), openPos, openOrd);
    }

    private RunContext buildContext(Run r) {
        return new RunContext(r.id(), RunMode.PAPER, r.aiMode(), r.initialCapital(), props.risk(), ExecutionAssumptions.from(props.paper()),
                r.dataSource(), null, false);
    }

    private void recordRunConfig() {
        try {
            Map<String, Object> cfg = new java.util.LinkedHashMap<>();
            cfg.put("strategies_effective", engine.recordStrategyConfigs(ctx, strategyConfigs));
            cfg.put("risk", props.risk());
            cfg.put("execution", ctx.exec());
            cfg.put("analyst_provider", props.ai().provider());
            cfg.put("model", props.ai().model());
            cfg.put("prompt_version", props.ai().promptVersion());
            runs.setConfig(run.id(), mapper.writeValueAsString(cfg));
        } catch (Exception e) {
            log.warn("could not record run configuration: {}", e.getClass().getSimpleName());
        }
    }

    /** Archive the completed candles just analysed (idempotent; source-labelled). */
    private void archiveCandles() {
        String interval = props.data().timeframeMinutes() + "m";
        for (Instrument i : uni) {
            try {
                List<Candle> c = view.candles(i, props.data().timeframeMinutes());
                if (!c.isEmpty()) candleArchive.insertIfAbsent(i.id(), interval, c.get(c.size() - 1), props.data().source());
            } catch (RuntimeException ignored) { /* archive is best-effort */ }
        }
    }

    public Optional<RunContext> context() { return Optional.ofNullable(ctx); }
    public Optional<Run> currentRun() { return Optional.ofNullable(run); }
    public Instant lastTickAt() { return lastTickAt; }
    public String lastError() { return lastError; }
    public Map<String, Double> marks() { return Map.copyOf(marks); }
    public boolean replayMode() { return clock instanceof SettableClock; }
    public Instant now() { return clock.now(); }

    /** Starts a fresh PAPER run (e.g. to change the AI mode) — only when flat, so P&L streams never mix. */
    public synchronized Run startNewRun(AiMode mode, String name, String actor) {
        if (!trades.openPositions(run.id()).isEmpty() || !trades.openOrders(run.id()).isEmpty())
            throw new IllegalStateException("cannot start a new paper run while positions or orders are open");
        runs.finish(run.id(), "ENDED", null);
        engine.forgetRun(run.id());
        Run nr = runs.create(RunMode.PAPER, name, mode, props.data().source(), props.paper().initialCapital(), null, null, null, null);
        this.run = nr;
        this.ctx = buildContext(nr);
        this.lastEvaluatedBoundary = Instant.EPOCH;
        marks.clear();
        recordRunConfig();
        audit.log(actor, "PAPER_RUN_STARTED", "run", nr.id(), "aiMode=" + mode);
        return nr;
    }

    @Scheduled(fixedDelay = 1000)
    public synchronized void tick() {
        if (!props.scheduler().enabled() || ctx == null || stopping) return;
        boolean replay = replayMode();
        long interval = (replay ? props.mock().replayStepSeconds() : props.scheduler().pollSeconds()) * 1000L;
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastActionMs < interval) return;
        lastActionMs = nowMs;
        Optional<String> lock = store.tryLock("paper-tick", Duration.ofSeconds(60));
        if (lock.isEmpty()) return;
        try {
            if (replay) advanceReplayClock();
            runCycle();
            lastError = null;
        } catch (RuntimeException e) {
            StackTraceElement top = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage() + (top == null ? "" : " at " + top);
            log.warn("paper cycle failed: {}", lastError, e);
        } finally {
            store.unlock("paper-tick", lock.get());
        }
    }

    /** One cycle at the clock's current time. Visible for tests (they drive time explicitly). */
    public synchronized void runCycle() {
        view.refresh();
        Instant now = clock.now();
        LocalDate day = session.dateOf(now);
        lastTickAt = Instant.now();
        store.put("last_tick", now.toString(), Duration.ofMinutes(10));
        if (lastActiveDay != null && lastActiveDay.isBefore(day) && !day.equals(lastFinalizedDay)) finishDay(lastActiveDay);
        if (!session.isTradingDay(day)) return;
        if (session.isOpen(now)) {
            if (uni.isEmpty()) uni = universe.analysisUniverse();
            lastActiveDay = day;
            engine.executionTick(ctx, view);
            Instant boundary = lastBoundary(now);
            if (boundary != null && boundary.isAfter(lastEvaluatedBoundary)) {
                TradingEngine.StepResult r = engine.step(ctx, view, uni, props.ai().maxCallsPerDay());
                lastEvaluatedBoundary = boundary;
                archiveCandles();
                com.aiinvestor.api.EventBroadcaster eb = events.getIfAvailable();
                if (eb != null) eb.publish("step", Map.of("as_of", now.toString(), "evaluated", r.evaluated(), "candidates", r.candidates(),
                        "approved", r.approved(), "rejected", r.rejected()));
                log.debug("step {}: evaluated={} candidates={} approved={} rejected={}", boundary, r.evaluated(), r.candidates(), r.approved(), r.rejected());
            }
            updateMarks();
        } else if (!now.isBefore(session.closeInstant(day)) && lastActiveDay != null && lastActiveDay.equals(day) && !day.equals(lastFinalizedDay)) {
            finishDay(day);
        }
    }

    private Instant lastBoundary(Instant now) {
        Instant open = session.openInstant(session.dateOf(now));
        long tfSec = props.data().timeframeMinutes() * 60L;
        long elapsed = Duration.between(open, now).getSeconds();
        if (elapsed < tfSec) return null;
        return open.plusSeconds((elapsed / tfSec) * tfSec);
    }

    private void finishDay(LocalDate day) {
        try {
            engine.executionTick(ctx, view);
            int n = engine.finalizeDay(ctx, view, day);
            List<String> issues = reconciliation.endOfDay(run.id(), "PAPER", day);
            lastFinalizedDay = day;
            marks.clear();
            log.info("day {} finalised: {} outcomes recorded, reconciliation issues={}", day, n, issues.size());
        } catch (RuntimeException e) {
            log.warn("end-of-day failed for {}: {}", day, e.getMessage());
            lastError = "end-of-day: " + e.getMessage();
        }
    }

    private void advanceReplayClock() {
        SettableClock sc = (SettableClock) clock;
        Instant now = sc.now();
        LocalDate day = session.dateOf(now);
        Instant next = now.plus(Duration.ofMinutes(props.data().timeframeMinutes()));
        if (next.isAfter(session.closeInstant(day))) {
            sc.set(session.closeInstant(day));
            view.refresh();
            if (lastActiveDay != null && !day.equals(lastFinalizedDay)) finishDay(day);
            LocalDate nd = session.nextTradingDay(day);
            sc.set(session.openInstant(nd).plus(Duration.ofMinutes(props.data().timeframeMinutes())));
        } else {
            sc.set(next);
        }
    }

    private void updateMarks() {
        for (PositionRec p : trades.openPositions(run.id())) {
            try {
                Instrument i = instrumentRepo.findById(p.instrumentId()).orElse(null);
                if (i == null) continue;
                List<Candle> recent = view.bars1m(i, clock.now().minus(Duration.ofMinutes(15)), clock.now());
                if (!recent.isEmpty()) marks.put(p.id(), recent.get(recent.size() - 1).close());
            } catch (RuntimeException ignored) { /* marks are best-effort display data */ }
        }
    }

    @PreDestroy
    void shutdown() {
        stopping = true;
        synchronized (this) {   // wait for an in-flight cycle to finish
            log.info("paper trading stopped; open positions/orders remain persisted for restart recovery");
        }
    }
}
