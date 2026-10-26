package com.aiinvestor.engine;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.indicators.IndicatorSet;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.persistence.TradeRepository.PositionRec;
import com.aiinvestor.strategy.CandleContext;
import com.aiinvestor.strategy.Strategy;
import com.aiinvestor.strategy.StrategyResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the compact, validated snapshot that is both persisted (reproducibility) and sent to the analyst.
 * Contains market data and strategy output only: no credentials, account ids or free-form external text.
 */
@Component
public class SnapshotBuilder {
    public record RiskBudget(double dailyLossRemaining, int tradesRemaining, int openPositions, double maxRiskPerTrade) {}

    private final AppProperties props;
    private final MarketSession session;

    public SnapshotBuilder(AppProperties props, MarketSession session) {
        this.props = props;
        this.session = session;
    }

    public Map<String, Object> build(RunContext run, CandleContext ctx, IndicatorSet ind, Strategy strategy, StrategyResult res,
                                     Quote quote, boolean syntheticQuote, Map<String, Object> optionPlan, PositionRec openPosition,
                                     RiskBudget budget, double avgBarVolume, String dataSource) {
        Map<String, Object> s = new LinkedHashMap<>();
        Instant now = ctx.asOf();
        s.put("snapshot_version", "1");
        s.put("data_source", dataSource.equals("MOCK") ? "MOCK_SYNTHETIC_DATA" : dataSource);
        s.put("as_of_utc", now.toString());
        s.put("as_of_local", now.atZone(session.zone()).toLocalDateTime().toString() + " " + session.zone());
        s.put("session_status", session.statusLabel(now));

        Map<String, Object> ins = new LinkedHashMap<>();
        ins.put("symbol", ctx.instrument().tradingSymbol());
        ins.put("exchange", ctx.instrument().exchange());
        ins.put("type", ctx.instrument().type().name());
        ins.put("tick_size", ctx.instrument().tickSize());
        s.put("instrument", ins);
        s.put("timeframe", ctx.timeframe().label());

        Candle last = ctx.lastBar();
        Map<String, Object> fresh = new LinkedHashMap<>();
        fresh.put("last_completed_bar_start", last.start().toString());
        fresh.put("last_completed_bar_end", ctx.lastBarEnd().toString());
        fresh.put("seconds_since_last_bar_end", Math.max(0, Duration.between(ctx.lastBarEnd(), now).getSeconds()));
        fresh.put("quote_age_seconds", quote == null ? null : Math.max(0, Duration.between(quote.timestamp(), now).getSeconds()));
        fresh.put("quote_source", quote == null ? null : quote.source());
        fresh.put("quote_is_derived_from_candles", syntheticQuote);
        s.put("data_freshness", fresh);

        int n = props.ai().maxCandlesInPrompt();
        List<Candle> all = ctx.candles();
        List<Map<String, Object>> candles = new java.util.ArrayList<>();
        for (Candle c : all.subList(Math.max(0, all.size() - n), all.size())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", c.start().atZone(session.zone()).toLocalDateTime().toString());
            m.put("o", c.open()); m.put("h", c.high()); m.put("l", c.low()); m.put("c", c.close()); m.put("v", c.volume());
            candles.add(m);
        }
        s.put("candles_completed_only", candles);
        s.put("indicators", ind.toMap());
        Map<String, Object> mk = new LinkedHashMap<>();
        mk.put("trend", ind.trend());
        mk.put("volatility", ind.volatility());
        s.put("market", mk);

        Map<String, Object> liq = new LinkedHashMap<>();
        if (quote != null && quote.hasBook()) {
            liq.put("bid", quote.bid()); liq.put("ask", quote.ask());
            liq.put("spread_bps", Math.round(quote.spreadFraction() * 1e6) / 100.0);
        } else {
            liq.put("bid", null); liq.put("ask", null); liq.put("spread_bps", null);
        }
        if (quote == null && ctx.instrument().isIndex()) liq.put("note", "index has no order book or traded volume");
        liq.put("avg_bar_volume_20", !ind.hasVolume() || Double.isNaN(avgBarVolume) ? null : Math.round(avgBarVolume));
        liq.put("volume_available", ind.hasVolume());
        s.put("liquidity", liq);

        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("strategy", strategy.name());
        prop.put("version", strategy.version());
        prop.put("decision", res.decision().name());
        prop.put("entry", res.entry());
        prop.put("stop", res.stop());
        prop.put("target", res.target());
        prop.put("risk_reward", res.isCandidate() ? Math.round(res.riskReward() * 100) / 100.0 : null);
        prop.put("supporting", res.supporting());
        prop.put("contradicting", res.contradicting());
        prop.put("invalidation", res.invalidation());
        s.put("strategy_proposal", prop);
        if (optionPlan != null) s.put("option_plan", optionPlan);

        if (openPosition == null) s.put("current_paper_position", null);
        else {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("side", openPosition.side().name()); p.put("quantity", openPosition.openQuantity());
            p.put("avg_entry", openPosition.avgEntry()); p.put("stop", openPosition.stop()); p.put("target", openPosition.target());
            s.put("current_paper_position", p);
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("daily_loss_remaining_inr", Math.round(budget.dailyLossRemaining()));
        b.put("trades_remaining_today", budget.tradesRemaining());
        b.put("open_positions", budget.openPositions());
        b.put("max_risk_per_trade_inr", Math.round(budget.maxRiskPerTrade()));
        s.put("remaining_risk_budget", b);
        return s;
    }
}
