package com.aiinvestor.api;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.engine.UniverseService;
import com.aiinvestor.indicators.Indicators;
import com.aiinvestor.market.HistoricalDataProvider;
import com.aiinvestor.market.MarketDataProvider;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.market.TradingClock;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Live Markets API: only instruments in the configured universe, always attributed to their data source. */
@RestController
@RequestMapping("/api/market")
public class MarketController {
    private static final Pattern SYMBOL = Pattern.compile("^[A-Za-z0-9&\\-_.]{1,40}$");
    private final UniverseService universe;
    private final MarketDataProvider quotes;
    private final HistoricalDataProvider history;
    private final IntegrationService integrations;
    private final AppProperties props;
    private final TradingClock clock;
    private final MarketSession session;

    public MarketController(UniverseService universe, MarketDataProvider quotes, HistoricalDataProvider history,
                            IntegrationService integrations, AppProperties props, TradingClock clock, MarketSession session) {
        this.universe = universe; this.quotes = quotes; this.history = history; this.integrations = integrations;
        this.props = props; this.clock = clock; this.session = session;
    }

    private Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", props.data().source());
        m.put("simulated", integrations.simulatedData());
        m.put("mechanism", integrations.simulatedData() ? "SYNTHETIC_REPLAY" : "REST_POLLING (not tick-by-tick streaming)");
        m.put("session", session.statusLabel(clock.now()));
        m.put("fetched_at", Instant.now());
        return m;
    }

    private Instrument resolve(String symbol) {
        if (symbol == null || !SYMBOL.matcher(symbol).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid symbol");
        return universe.analysisUniverse().stream().filter(i -> i.tradingSymbol().equalsIgnoreCase(symbol)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "symbol is not in the configured universe"));
    }

    @GetMapping("/instruments")
    public Map<String, Object> instruments(@RequestParam(defaultValue = "") String q) {
        Map<String, Object> out = meta();
        String block = integrations.marketDataBlockReason();
        if (block != null) { out.put("unavailable", block); out.put("instruments", List.of()); return out; }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Instrument i : universe.analysisUniverse()) {
            if (!needle.isEmpty() && !i.tradingSymbol().toLowerCase(Locale.ROOT).contains(needle)) continue;
            rows.add(Map.of("symbol", i.tradingSymbol(), "type", i.type().name(), "exchange", i.exchange(), "lot_size", i.lotSize()));
        }
        out.put("instruments", rows);
        return out;
    }

    @GetMapping("/quote")
    public Map<String, Object> quote(@RequestParam String symbol) {
        Map<String, Object> out = meta();
        String block = integrations.marketDataBlockReason();
        if (block != null) { out.put("unavailable", block); return out; }
        Instrument i = resolve(symbol);
        try {
            Quote q = quotes.getQuote(i);
            long age = Math.max(0, Duration.between(q.timestamp(), Instant.now()).getSeconds());
            out.put("symbol", i.tradingSymbol());
            out.put("last", q.last()); out.put("bid", q.hasBook() ? q.bid() : null); out.put("ask", q.hasBook() ? q.ask() : null);
            out.put("spread_pct", q.hasBook() ? q.spreadFraction() * 100 : null);
            out.put("volume", q.volume()); out.put("quote_time", q.timestamp()); out.put("age_seconds", age);
            out.put("stale", !integrations.simulatedData() && age > props.data().maxQuoteAgeSeconds() && session.isOpen(clock.now()));
        } catch (RuntimeException e) {
            out.put("unavailable", e.getClass().getSimpleName() + ": " + e.getMessage());   // never an invented quote
        }
        return out;
    }

    @GetMapping("/candles")
    public Map<String, Object> candles(@RequestParam String symbol, @RequestParam(defaultValue = "5") int minutes, @RequestParam(defaultValue = "3") int days) {
        if (!List.of(1, 5, 15, 30, 60).contains(minutes) || days < 1 || days > 10) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported timeframe/range");
        Map<String, Object> out = meta();
        String block = integrations.marketDataBlockReason();
        if (block != null) { out.put("unavailable", block); out.put("candles", List.of()); return out; }
        Instrument i = resolve(symbol);
        try {
            Instant now = clock.now();
            List<Candle> c = history.getCandles(i, minutes, now.minus(Duration.ofDays(days)), now);
            List<Candle> done = c.stream().filter(x -> !x.end(minutes).isAfter(now)).toList();
            double[] closes = done.stream().mapToDouble(Candle::close).toArray();
            double[] ema9 = Indicators.ema(closes, 9), ema21 = Indicators.ema(closes, 21), rsi = Indicators.rsi(closes, 14);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int k = 0; k < done.size(); k++) {
                Candle x = done.get(k);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("t", x.start().getEpochSecond()); r.put("o", x.open()); r.put("h", x.high()); r.put("l", x.low()); r.put("c", x.close()); r.put("v", x.volume());
                r.put("ema9", fin(ema9, k)); r.put("ema21", fin(ema21, k)); r.put("rsi14", fin(rsi, k));
                rows.add(r);
            }
            out.put("symbol", i.tradingSymbol()); out.put("minutes", minutes); out.put("candles", rows);
            out.put("last_candle_time", done.isEmpty() ? null : done.get(done.size() - 1).start());
        } catch (RuntimeException e) {
            out.put("unavailable", e.getClass().getSimpleName() + ": " + e.getMessage());
            out.put("candles", List.of());
        }
        return out;
    }

    private static Double fin(double[] a, int k) { return k < a.length && Double.isFinite(a[k]) ? a[k] : null; }
}
