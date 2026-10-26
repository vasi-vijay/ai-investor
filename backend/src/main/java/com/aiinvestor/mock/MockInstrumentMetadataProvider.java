package com.aiinvestor.mock;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Enums.InstrumentType;
import com.aiinvestor.domain.Enums.OptionType;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.market.InstrumentMetadataProvider;
import com.aiinvestor.market.MarketSession;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Synthetic instrument master. Expiry rule, lot sizes and strike grid here are MOCK values for local testing only;
 * the Groww provider reads the real values from the broker's instrument master.
 */
public class MockInstrumentMetadataProvider implements InstrumentMetadataProvider {
    public static final String SOURCE = "MOCK";
    private final SyntheticMarket market;
    private final MarketSession session;
    private final AppProperties props;
    private final Map<String, Instrument> bySymbol = new ConcurrentHashMap<>();
    private final Map<String, List<Instrument>> contractCache = new ConcurrentHashMap<>();

    public MockInstrumentMetadataProvider(SyntheticMarket market, MarketSession session, AppProperties props) {
        this.market = market;
        this.session = session;
        this.props = props;
    }

    @Override public Optional<Instrument> findEquity(String symbol) {
        if (!props.universe().equities().contains(symbol)) return Optional.empty();
        return Optional.of(bySymbol.computeIfAbsent("EQ|" + symbol, k -> new Instrument(null, "NSE", "CASH",
                InstrumentType.EQUITY, symbol, "NSE-" + symbol, symbol + " (mock)", null, null, null, null, 1,
                new BigDecimal("0.05"), "MOCK-" + symbol, true, SOURCE)));
    }

    @Override public Optional<Instrument> findIndex(String underlying) {
        if (!props.universe().indexUnderlyings().contains(underlying)) return Optional.empty();
        return Optional.of(bySymbol.computeIfAbsent("IDX|" + underlying, k -> new Instrument(null, "NSE", "CASH",
                InstrumentType.INDEX, underlying, "NSE-" + underlying, underlying + " index (mock)", underlying, null, null,
                null, 1, new BigDecimal("0.05"), "MOCK-IDX-" + underlying, false, SOURCE)));
    }

    /** MOCK rule: the next four Thursdays. Real expiries come only from the broker's data. */
    @Override public List<LocalDate> optionExpiries(String underlying, LocalDate from) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = from;
        while (out.size() < 4) {
            if (d.getDayOfWeek() == DayOfWeek.THURSDAY) out.add(d);
            d = d.plusDays(1);
        }
        return out;
    }

    @Override public List<Instrument> optionContracts(String underlying, LocalDate expiry) {
        return contractCache.computeIfAbsent(underlying + "|" + expiry, k -> {
            // centre the strike grid on the synthetic spot around expiry (the synthetic index drifts away from its base price)
            double base = market.dayOpen(underlying, expiry);
            double step = underlying.equals("BANKNIFTY") ? 100 : 50;
            int lot = market.lotSize(underlying);
            double lo = Math.floor(base * 0.88 / step) * step;
            double hi = Math.ceil(base * 1.12 / step) * step;
            List<Instrument> out = new ArrayList<>();
            for (double strikeV = lo; strikeV <= hi; strikeV += step) {
                final double strike = strikeV;
                for (OptionType ot : OptionType.values()) {
                    String sym = underlying + "-" + expiry + "-" + (long) strike + "-" + ot;
                    out.add(bySymbol.computeIfAbsent(sym, s -> new Instrument(null, "NSE", "FNO", InstrumentType.OPTION,
                            s, s, s + " (mock)", underlying, expiry, BigDecimal.valueOf(strike), ot, lot,
                            new BigDecimal("0.05"), "MOCK-" + s, true, SOURCE)));
                }
            }
            return out;
        });
    }

    @Override public String sourceName() { return SOURCE; }
}
