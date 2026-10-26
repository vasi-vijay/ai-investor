package com.aiinvestor.market;

import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;

import java.util.List;
import java.util.Map;

public interface MarketDataProvider {
    /** Latest quote; throws {@link ProviderException} when unavailable. */
    Quote getQuote(Instrument instrument);

    /** Keyed by trading symbol; instruments without data are simply absent. */
    Map<String, Quote> getQuotes(List<Instrument> instruments);

    boolean isConnected();

    String sourceName();
}
