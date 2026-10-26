package com.aiinvestor.engine;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Enums.Decision;
import com.aiinvestor.domain.Enums.OptionType;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Money;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.market.InstrumentMetadataProvider;
import com.aiinvestor.market.MarketSession;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Maps an index-direction candidate to a concrete LONG option contract using authoritative metadata and live liquidity.
 * Direction alone never selects the contract: expiry (never expiry-day by default), strike, premium range, spread,
 * volume and recent premium movement all filter candidates. Only long calls (bullish) and long puts (bearish) are produced.
 */
@Component
public class OptionPlanner {
    public record Plan(Instrument contract, Quote quote, BigDecimal entry, BigDecimal stop, BigDecimal target,
                       double recentPremiumMove, Map<String, Object> details) {}

    public record Result(Plan plan, String rejection, List<String> notes) {
        static Result ok(Plan p, List<String> n) { return new Result(p, null, n); }
        static Result reject(String r, List<String> n) { return new Result(null, r, n); }
    }

    private final InstrumentMetadataProvider metadata;
    private final MarketSession session;
    private final UniverseService universe;
    private final AppProperties props;

    public OptionPlanner(InstrumentMetadataProvider metadata, MarketSession session, UniverseService universe, AppProperties props) {
        this.metadata = metadata;
        this.session = session;
        this.universe = universe;
        this.props = props;
    }

    public Result plan(RunContext run, Instrument underlying, Decision decision, double spot, MarketView view, int tfMinutes) {
        AppProperties.OptionRisk o = run.risk().options();
        List<String> notes = new ArrayList<>();
        LocalDate today = session.dateOf(view.asOf());
        List<LocalDate> expiries = metadata.optionExpiries(underlying.tradingSymbol(), today);
        Optional<LocalDate> expiry = expiries.stream().filter(e -> ChronoUnit.DAYS.between(today, e) >= o.minDaysToExpiry()).findFirst();
        if (expiry.isEmpty()) return Result.reject("no expiry with at least " + o.minDaysToExpiry() + " day(s) to expiry (available: " + expiries + ")", notes);
        notes.add("expiry " + expiry.get() + " (" + ChronoUnit.DAYS.between(today, expiry.get()) + "d)");

        OptionType type = decision == Decision.BUY_CANDIDATE ? OptionType.CE : OptionType.PE;
        List<Instrument> contracts = metadata.optionContracts(underlying.tradingSymbol(), expiry.get()).stream()
                .filter(c -> c.optionType() == type && c.tradable() && c.strike() != null).toList();
        if (contracts.isEmpty()) return Result.reject("no " + type + " contracts for " + underlying.tradingSymbol() + " " + expiry.get(), notes);

        List<BigDecimal> strikes = new ArrayList<>(new TreeSet<>(contracts.stream().map(Instrument::strike).toList()));
        int atm = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < strikes.size(); i++) {
            double d = Math.abs(strikes.get(i).doubleValue() - spot);
            if (d < best) { best = d; atm = i; }
        }
        int steps = o.strikeStepsFromAtm();
        int target = type == OptionType.CE ? atm - steps : atm + steps;
        target = Math.max(0, Math.min(strikes.size() - 1, target));

        List<Candidate> evaluated = new ArrayList<>();
        for (int idx = Math.max(0, target - 1); idx <= Math.min(strikes.size() - 1, target + 1); idx++) {
            BigDecimal strike = strikes.get(idx);
            Instrument c = contracts.stream().filter(x -> x.strike().compareTo(strike) == 0).findFirst().orElseThrow();
            evaluated.add(evaluate(run, c, view, tfMinutes, Math.abs(idx - target)));
        }
        Optional<Candidate> pick = evaluated.stream().filter(c -> c.problem == null).min(Comparator.comparingDouble((Candidate c) -> c.score));
        if (pick.isEmpty()) {
            String why = evaluated.stream().map(c -> c.contract.strike() + type.name() + ": " + c.problem).reduce((a, b) -> a + " | " + b).orElse("none evaluated");
            return Result.reject("no liquid " + type + " contract passed pre-filters (" + why + ")", notes);
        }
        Candidate c = pick.get();
        Instrument persisted = universe.persist(c.contract);
        notes.add("selected " + persisted.tradingSymbol() + " strike " + persisted.strike() + " premium ~" + c.entry);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("contract", persisted.tradingSymbol());
        d.put("option_type", type.name());
        d.put("strike", persisted.strike());
        d.put("expiry", persisted.expiry().toString());
        d.put("days_to_expiry", ChronoUnit.DAYS.between(today, persisted.expiry()));
        d.put("lot_size", persisted.lotSize());
        d.put("tick_size", persisted.tickSize());
        d.put("underlying_spot", Math.round(spot * 100) / 100.0);
        d.put("moneyness_steps_vs_atm", type == OptionType.CE ? atm - strikes.indexOf(persisted.strike()) : strikes.indexOf(persisted.strike()) - atm);
        d.put("premium_entry", c.entry);
        d.put("premium_stop", c.stop);
        d.put("premium_target", c.target);
        d.put("bid", c.quote.bid());
        d.put("ask", c.quote.ask());
        d.put("spread_pct", Math.round(c.spreadFraction * 10_000) / 10_000.0);
        d.put("day_volume", c.quote.volume());
        d.put("open_interest", c.quote.openInterest());
        d.put("recent_premium_move_fraction", Math.round(c.recentMove * 10_000) / 10_000.0);
        d.put("quote_is_synthetic", view.syntheticQuotes());
        d.put("metadata_source", persisted.source());
        return Result.ok(new Plan(persisted, c.quote, c.entry, c.stop, c.target, c.recentMove, d), notes);
    }

    private static class Candidate {
        Instrument contract; Quote quote; BigDecimal entry, stop, target; double spreadFraction, recentMove, score; String problem;
    }

    private Candidate evaluate(RunContext run, Instrument c, MarketView view, int tf, int offsetFromTarget) {
        AppProperties.OptionRisk o = run.risk().options();
        Candidate r = new Candidate();
        r.contract = c;
        Optional<Quote> q = view.quote(c);
        if (q.isEmpty()) { r.problem = "no quote"; return r; }
        r.quote = q.get();
        double ref = r.quote.hasBook() ? r.quote.ask() : r.quote.last();
        BigDecimal entry = Money.ceilToTick(BigDecimal.valueOf(ref), c.tickSize());
        double premium = entry.doubleValue();
        if (premium < o.minPremium() || premium > o.maxPremium()) { r.problem = String.format(Locale.ROOT, "premium %.2f outside %.2f–%.2f", premium, o.minPremium(), o.maxPremium()); return r; }
        r.spreadFraction = r.quote.hasBook() ? r.quote.spreadFraction() : run.exec().assumedOptionSpreadPct() / 100.0;
        if (r.spreadFraction > o.maxSpreadPct()) { r.problem = String.format(Locale.ROOT, "spread %.2f%% too wide", r.spreadFraction * 100); return r; }
        if (r.quote.volume() < o.minVolume()) { r.problem = "volume " + r.quote.volume() + " below " + o.minVolume(); return r; }
        Instant from = view.asOf().minus(Duration.ofMinutes((long) tf * o.premiumMoveLookbackBars()));
        List<Candle> recent = view.bars1m(c, from, view.asOf());
        r.recentMove = recent.size() < 2 ? 0 : Math.abs(recent.get(recent.size() - 1).close() - recent.get(0).open()) / recent.get(0).open();
        r.entry = entry;
        BigDecimal stop = Money.roundToTick(entry.multiply(BigDecimal.valueOf(1 - o.stopLossPct())), c.tickSize());
        if (stop.compareTo(c.tickSize()) < 0) stop = c.tickSize();
        BigDecimal risk = entry.subtract(stop);
        if (risk.signum() <= 0) { r.problem = "stop not below entry after tick rounding"; return r; }
        r.stop = stop;
        r.target = Money.roundToTick(entry.add(risk.multiply(BigDecimal.valueOf(o.rewardMultiple()))), c.tickSize());
        r.score = r.spreadFraction + 0.002 * offsetFromTarget;
        return r;
    }
}
