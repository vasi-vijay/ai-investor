package com.aiinvestor.risk;

import com.aiinvestor.domain.Enums.RunMode;
import com.aiinvestor.engine.RunContext;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.persistence.DailyStateRepository;
import com.aiinvestor.persistence.DailyStateRepository.DayState;
import com.aiinvestor.persistence.RiskEventRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Applies the persistent daily loss lock after every closed trade. A set lock is never cleared by code. */
@Component
public class RiskGuard {
    private final DailyStateRepository daily;
    private final RiskEventRepository events;
    private final MarketSession session;

    public RiskGuard(DailyStateRepository daily, RiskEventRepository events, MarketSession session) {
        this.daily = daily;
        this.events = events;
        this.session = session;
    }

    public void onTradeClosed(RunContext run, Instant closedAt, BigDecimal netPnl, BigDecimal costs, String signalId) {
        LocalDate day = session.dateOf(closedAt);
        daily.recordClosedTrade(run.runId(), run.mode(), day, netPnl, costs);
        DayState ds = daily.get(run.runId(), day);
        BigDecimal limit = run.risk().global().maxDailyLoss();
        if (ds.realizedNet().add(limit).signum() <= 0) {
            String reason = "daily loss limit reached: realized net " + ds.realizedNet() + " <= -" + limit;
            if (daily.lock(run.runId(), run.mode(), day, reason, closedAt)) {
                events.record(run.runId(), run.mode().name(), signalId, "DAILY_LOCK", "DAILY_LOSS_LIMIT", "CRITICAL", reason);
            }
        }
    }
}
