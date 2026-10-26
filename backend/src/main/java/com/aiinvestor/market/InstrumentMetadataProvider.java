package com.aiinvestor.market;

import com.aiinvestor.domain.Instrument;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Authoritative instrument metadata. Implementations must source lot size, tick size, expiry and strike from the
 * broker/exchange data (or be clearly labelled as mock). Callers must never hardcode these values.
 */
public interface InstrumentMetadataProvider {
    Optional<Instrument> findEquity(String symbol);

    /** The index itself. Indices are NOT directly tradable as cash equity; use {@link #optionContracts}. */
    Optional<Instrument> findIndex(String underlying);

    /** Expiries (on or after {@code from}) for which option contracts exist, ascending. */
    List<LocalDate> optionExpiries(String underlying, LocalDate from);

    List<Instrument> optionContracts(String underlying, LocalDate expiry);

    String sourceName();
}
