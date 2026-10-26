package com.aiinvestor.groww;

import com.aiinvestor.broker.BrokerException;
import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.market.CandleSeries;
import com.aiinvestor.market.HistoricalDataProvider;
import com.aiinvestor.market.MarketDataProvider;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.market.ProviderException;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Live quotes (GET /live-data/quote) and historical candles (GET /historical/candles) from Groww.
 * Both require a market-data entitlement on the account; a 403 surfaces as a clear ProviderException and the engine then
 * produces no executable signals (data gate). Streaming (NATS/protobuf) is NOT implemented: polling is used.
 */
public class GrowwMarketData implements MarketDataProvider, HistoricalDataProvider {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final GrowwHttp http;
    private final GrowwAuthManager auth;
    private final MarketSession session;

    public GrowwMarketData(GrowwHttp http, GrowwAuthManager auth, MarketSession session) {
        this.http = http;
        this.auth = auth;
        this.session = session;
    }

    @Override public Quote getQuote(Instrument i) {
        try {
            JsonNode p = http.get("/live-data/quote", Map.of("exchange", i.exchange(), "segment", i.segment(), "trading_symbol", i.tradingSymbol()));
            Double last = GrowwJson.num(p, "last_price", "ltp");
            if (last == null) throw new ProviderException("quote for " + i.tradingSymbol() + " has no last price");
            double bid = firstDepth(p.path("depth").path("buy"), GrowwJson.num(p, "bid_price") == null ? 0 : GrowwJson.num(p, "bid_price"));
            double ask = firstDepth(p.path("depth").path("sell"), GrowwJson.num(p, "offer_price", "ask_price") == null ? 0 : GrowwJson.num(p, "offer_price", "ask_price"));
            long vol = GrowwJson.num(p, "volume") == null ? 0 : GrowwJson.num(p, "volume").longValue();
            Double oi = GrowwJson.num(p, "open_interest");
            return new Quote(i.tradingSymbol(), last, bid, ask, GrowwJson.integer(p, 0, "bid_quantity"), GrowwJson.integer(p, 0, "offer_quantity"),
                    vol, oi == null ? null : oi.longValue(), Instant.now(), "GROWW");
        } catch (BrokerException e) {
            throw new ProviderException(describe("quote", i, e), e);
        }
    }

    private static double firstDepth(JsonNode levels, double fallback) {
        if (levels != null && levels.isArray() && levels.size() > 0) {
            Double d = GrowwJson.num(levels.get(0), "price");
            if (d != null && d > 0) return d;
        }
        return fallback;
    }

    @Override public Map<String, Quote> getQuotes(List<Instrument> instruments) {
        Map<String, Quote> out = new HashMap<>();
        for (Instrument i : instruments) {
            try { out.put(i.tradingSymbol(), getQuote(i)); } catch (ProviderException e) { if (e.getCause() instanceof BrokerException.Auth) throw e; }
        }
        return out;
    }

    @Override public List<Candle> getCandles(Instrument i, int intervalMinutes, Instant from, Instant to) {
        String interval = intervalLabel(intervalMinutes);
        if (i.growwSymbol() == null) throw new ProviderException("instrument " + i.tradingSymbol() + " has no groww_symbol");
        List<Candle> out = new ArrayList<>();
        ZoneId z = session.zone();
        LocalDate d = session.dateOf(from);
        LocalDate last = session.dateOf(to.minusMillis(1));
        try {
            while (!d.isAfter(last)) {                       // one request per day: range limits per request are UNVERIFIED
                if (session.isTradingDay(d)) {
                    LocalDateTime s = session.openInstant(d).atZone(z).toLocalDateTime(), e = session.closeInstant(d).atZone(z).toLocalDateTime();
                    JsonNode p = http.get("/historical/candles", Map.of("exchange", i.exchange(), "segment", i.segment(),
                            "groww_symbol", i.growwSymbol(), "start_time", s.format(FMT), "end_time", e.format(FMT), "candle_interval", interval));
                    parseCandles(p.path("candles"), z, out);
                }
                d = d.plusDays(1);
            }
        } catch (BrokerException e) {
            throw new ProviderException(describe("historical candles", i, e), e);
        }
        out.removeIf(c -> c.start().isBefore(from) || !c.start().isBefore(to));
        out.sort(Comparator.comparing(Candle::start));
        return out;
    }

    /** Each candle is an array [timestamp, open, high, low, close, volume(, open_interest)] — layout UNVERIFIED; invalid rows are dropped. */
    static void parseCandles(JsonNode arr, ZoneId zone, List<Candle> out) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode row : arr) {
            if (!row.isArray() || row.size() < 6) continue;
            Instant ts = parseTs(row.get(0), zone);
            if (ts == null) continue;
            Long oi = row.size() > 6 && row.get(6).isNumber() ? row.get(6).asLong() : null;
            Candle c = new Candle(ts, row.get(1).asDouble(), row.get(2).asDouble(), row.get(3).asDouble(), row.get(4).asDouble(), row.get(5).asLong(), oi);
            if (c.isConsistent()) out.add(c);
        }
    }

    private static Instant parseTs(JsonNode n, ZoneId zone) {
        if (n.isNumber()) {
            long v = n.asLong();
            return v > 100_000_000_000L ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v);
        }
        String s = n.asText();
        try { return LocalDateTime.parse(s.replace(' ', 'T')).atZone(zone).toInstant(); } catch (RuntimeException e) { }
        try { return Instant.parse(s); } catch (RuntimeException e) { return null; }
    }

    static String intervalLabel(int minutes) {
        return switch (minutes) {
            case 1 -> "1minute"; case 2 -> "2minute"; case 3 -> "3minute"; case 5 -> "5minute"; case 10 -> "10minute";
            case 15 -> "15minute"; case 30 -> "30minute"; case 60 -> "1hour"; case 240 -> "4hour";
            default -> throw new ProviderException("unsupported candle interval " + minutes + "m for Groww");
        };
    }

    private static String describe(String what, Instrument i, BrokerException e) {
        String hint = e instanceof BrokerException.NotEntitled ? " (account/subscription lacks this market-data entitlement)"
                : e instanceof BrokerException.Auth ? " (authorisation expired — operations paused)" : "";
        return "Groww " + what + " failed for " + i.tradingSymbol() + ": " + e.getMessage() + hint;
    }

    @Override public boolean isConnected() { return auth.state() == GrowwAuthManager.State.OK; }
    @Override public String sourceName() { return "GROWW"; }
}
