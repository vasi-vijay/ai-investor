package com.aiinvestor.support;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Enums.*;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.engine.ExecutionAssumptions;
import com.aiinvestor.engine.RunContext;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.persistence.InstrumentRepository;
import com.aiinvestor.persistence.RunRepository;
import com.aiinvestor.persistence.RunRepository.Run;
import com.aiinvestor.risk.RiskModels.RiskRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/** Test-only helpers to create runs, instruments and risk requests quickly. */
@Component
public class Fixtures {
    public static final LocalDate TUESDAY = LocalDate.of(2025, 3, 4);
    private final RunRepository runs;
    private final InstrumentRepository instruments;
    private final AppProperties props;
    private final MarketSession session;

    public Fixtures(RunRepository runs, InstrumentRepository instruments, AppProperties props, MarketSession session) {
        this.runs = runs; this.instruments = instruments; this.props = props; this.session = session;
    }

    public Instant at(LocalDate d, int hour, int minute) { return session.openInstant(d).plus(Duration.ofMinutes((hour - 9) * 60L + (minute - 15))); }

    public Instant tuesday1000() { return at(TUESDAY, 10, 0); }

    public Run run(RunMode mode, AiMode ai) {
        return runs.create(mode, "test-" + mode, ai, "MOCK", new BigDecimal("1000000"), null, null, null, null);
    }

    public RunContext ctx(Run r) { return ctx(r, props.risk()); }

    public RunContext ctx(Run r, AppProperties.Risk risk) {
        return new RunContext(r.id(), r.mode(), r.aiMode(), r.initialCapital(), risk, ExecutionAssumptions.from(props.paper()), "MOCK", null, false);
    }

    public Instrument equity(String symbol) {
        return instruments.upsert(new Instrument(null, "NSE", "CASH", InstrumentType.EQUITY, symbol, "NSE-" + symbol, symbol, null, null, null, null, 1,
                new BigDecimal("0.05"), "T-" + symbol, true, "TEST"));
    }

    public Instrument option(String symbol, LocalDate expiry, int lot, OptionType type) {
        return instruments.upsert(new Instrument(null, "NSE", "FNO", InstrumentType.OPTION, symbol, symbol, symbol, "NIFTY", expiry, new BigDecimal("24500"), type, lot,
                new BigDecimal("0.05"), "T-" + symbol, true, "TEST"));
    }

    public Quote quote(String symbol, double bid, double ask, Instant ts, long volume) {
        return new Quote(symbol, (bid + ask) / 2, bid, ask, 1000, 1000, volume, 100_000L, ts, "TEST");
    }

    public RiskRequest equityLong(Instrument i, double entry, double stop, double target, Quote q, Instant now, double avgVol) {
        return new RiskRequest("sig", i, Direction.LONG, Side.BUY, bd(entry), bd(stop), bd(target), q, false, now, avgVol, 0, 0);
    }

    public RiskRequest optionLong(Instrument i, double entry, double stop, double target, Quote q, Instant now, double premiumMove) {
        return new RiskRequest("sig", i, Direction.LONG, Side.BUY, bd(entry), bd(stop), bd(target), q, false, now, 0, premiumMove, 0);
    }

    public static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }
}
