package com.aiinvestor.domain;

import com.aiinvestor.domain.Enums.InstrumentType;
import com.aiinvestor.domain.Enums.OptionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Instrument metadata. For options every field (expiry, strike, lot size, tick size) must come from an
 * authoritative provider; this class never derives them.
 */
public record Instrument(
        Long id,
        String exchange,
        String segment,
        InstrumentType type,
        String tradingSymbol,
        String growwSymbol,
        String name,
        String underlying,
        LocalDate expiry,
        BigDecimal strike,
        OptionType optionType,
        int lotSize,
        BigDecimal tickSize,
        String exchangeToken,
        boolean tradable,
        String source) {

    public boolean isOption() { return type == InstrumentType.OPTION; }
    public boolean isIndex() { return type == InstrumentType.INDEX; }
    public boolean isEquity() { return type == InstrumentType.EQUITY; }

    public Instrument withId(Long newId) {
        return new Instrument(newId, exchange, segment, type, tradingSymbol, growwSymbol, name, underlying,
                expiry, strike, optionType, lotSize, tickSize, exchangeToken, tradable, source);
    }

    public String label() { return tradingSymbol; }
}
