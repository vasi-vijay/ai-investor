package com.aiinvestor.api;

import com.aiinvestor.ai.AiAnalysisService;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.groww.GrowwAuthManager;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.persistence.DailyStateRepository;
import com.aiinvestor.persistence.DailyStateRepository.DayState;
import com.aiinvestor.persistence.RunRepository.Run;
import com.aiinvestor.persistence.SettingsRepository;
import com.aiinvestor.persistence.TradeRepository;
import com.aiinvestor.persistence.TradeRepository.PositionRec;
import com.aiinvestor.risk.KillSwitch;
import com.aiinvestor.runtime.PaperTradingService;
import com.aiinvestor.runtime.TransientStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OverviewService {
    private final PaperTradingService paper;
    private final TradeRepository trades;
    private final DailyStateRepository daily;
    private final MarketSession session;
    private final AppProperties props;
    private final AiAnalysisService ai;
    private final ObjectProvider<GrowwAuthManager> groww;
    private final KillSwitch kill;
    private final SettingsRepository settings;
    private final TransientStore store;
    private final IntegrationService integrations;

    public OverviewService(PaperTradingService paper, TradeRepository trades, DailyStateRepository daily, MarketSession session,
                           AppProperties props, AiAnalysisService ai, ObjectProvider<GrowwAuthManager> groww, KillSwitch kill,
                           SettingsRepository settings, TransientStore store, IntegrationService integrations) {
        this.integrations = integrations;
        this.paper = paper; this.trades = trades; this.daily = daily; this.session = session; this.props = props;
        this.ai = ai; this.groww = groww; this.kill = kill; this.settings = settings; this.store = store;
    }

    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        Run run = paper.currentRun().orElse(null);
        Instant now = paper.now();
        m.put("mode", "PAPER");
        m.put("operating_state", integrations.state().name());
        m.put("data", integrations.data());
        m.put("engine_blocked_reason", paper.blockedReason());
        m.put("mode_note", "All executions are simulated. Live order placement is "
                + ("YES".equals(settings.get("live_authorized", "NO")) && props.live().enabled() ? "configured but gated by manual approval" : "DISABLED"));
        m.put("simulated", true);   // paper executions are always simulated; "data.simulated" says whether prices are too
        m.put("data_source", props.data().source());
        m.put("clock", Map.of("mode", paper.replayMode() ? "REPLAY_SIMULATED_TIME" : "REAL", "now_utc", now.toString(),
                "now_local", now.atZone(session.zone()).toLocalDateTime().toString() + " " + session.zone()));
        m.put("market_session", session.statusLabel(now));
        m.put("kill_switch", kill.isEngaged());
        m.put("transient_store", store.backend());

        GrowwAuthManager g = groww.getIfAvailable();
        Map<String, Object> gw = new LinkedHashMap<>();
        if (g == null) { gw.put("status", "NOT_USED"); gw.put("detail", "market data source is " + props.data().source()); }
        else { gw.put("status", g.state().name()); gw.put("detail", g.message()); }
        m.put("groww", gw);

        m.put("claude", integrations.claude());

        Map<String, Object> fresh = new LinkedHashMap<>();
        fresh.put("last_cycle_wall_clock", paper.lastTickAt());
        fresh.put("seconds_since_last_cycle", paper.lastTickAt() == null ? null : Math.max(0, Instant.now().getEpochSecond() - paper.lastTickAt().getEpochSecond()));
        fresh.put("last_error", paper.lastError());
        m.put("data_freshness", fresh);

        if (run == null) { m.put("paper_run", null); return m; }
        m.put("paper_run", Map.of("id", run.id(), "name", run.name(), "ai_mode", run.aiMode().name(), "started_at", run.startedAt()));
        BigDecimal initial = run.initialCapital();
        BigDecimal realizedTotal = trades.realizedNetTotal(run.id());
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal openNotional = BigDecimal.ZERO;
        List<PositionRec> open = trades.openPositions(run.id());
        Map<String, Double> marks = paper.marks();
        for (PositionRec p : open) {
            BigDecimal mark = marks.containsKey(p.id()) ? BigDecimal.valueOf(marks.get(p.id())) : p.avgEntry();
            BigDecimal per = p.side() == com.aiinvestor.domain.Enums.Direction.LONG ? mark.subtract(p.avgEntry()) : p.avgEntry().subtract(mark);
            unrealized = unrealized.add(per.multiply(BigDecimal.valueOf(p.openQuantity())));
            openNotional = openNotional.add(p.avgEntry().multiply(BigDecimal.valueOf(p.openQuantity())));
        }
        DayState ds = daily.get(run.id(), session.dateOf(now));
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("initial_simulated_capital", initial);
        cap.put("realized_pnl_total_net", realizedTotal);
        cap.put("unrealized_pnl", unrealized.setScale(2, RoundingMode.HALF_EVEN));
        cap.put("simulated_equity", initial.add(realizedTotal).add(unrealized).setScale(2, RoundingMode.HALF_EVEN));
        m.put("capital", cap);

        Map<String, Object> today = new LinkedHashMap<>();
        today.put("date", session.dateOf(now).toString());
        today.put("realized_pnl_net", ds.realizedNet());
        today.put("unrealized_pnl", unrealized.setScale(2, RoundingMode.HALF_EVEN));
        today.put("total_pnl_net", ds.realizedNet().add(unrealized).setScale(2, RoundingMode.HALF_EVEN));
        today.put("trading_costs", ds.costs());
        today.put("closed_trades", ds.trades());
        today.put("wins", ds.wins());
        today.put("losses", ds.losses());
        today.put("consecutive_losses", ds.consecutiveLosses());
        today.put("locked", ds.locked());
        today.put("lock_reason", ds.lockReason());
        m.put("today", today);

        AppProperties.Global g0 = props.risk().global();
        BigDecimal capital = initial.add(realizedTotal);
        Map<String, Object> ru = new LinkedHashMap<>();
        ru.put("daily_loss_limit", g0.maxDailyLoss());
        double lossUsed = Math.max(0, ds.realizedNet().negate().doubleValue());
        ru.put("daily_loss_used", BigDecimal.valueOf(lossUsed).setScale(2, RoundingMode.HALF_EVEN));
        ru.put("daily_loss_used_pct", pct(lossUsed, g0.maxDailyLoss().doubleValue()));
        ru.put("trades_used", ds.trades() + open.size());
        ru.put("trades_max", g0.maxTradesPerDay());
        ru.put("open_positions", open.size());
        ru.put("open_positions_max", g0.maxOpenPositions());
        ru.put("open_notional", openNotional.setScale(0, RoundingMode.HALF_EVEN));
        ru.put("capital_allocation_cap", capital.multiply(BigDecimal.valueOf(g0.maxCapitalAllocationPct())).setScale(0, RoundingMode.HALF_EVEN));
        ru.put("capital_allocation_used_pct", pct(openNotional.doubleValue(), capital.doubleValue() * g0.maxCapitalAllocationPct()));
        m.put("risk_utilization", ru);
        return m;
    }

    private static double pct(double used, double limit) { return limit <= 0 ? 0 : Math.round(used / limit * 1000) / 10.0; }
}
