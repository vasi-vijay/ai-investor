package com.aiinvestor.groww;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Enums.InstrumentType;
import com.aiinvestor.domain.Enums.OptionType;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.market.InstrumentMetadataProvider;
import com.aiinvestor.market.ProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/**
 * Instrument metadata from Groww's published instrument master CSV (URL taken from the official SDK). Lot size, tick
 * size, expiry and strike are read from the file — never hardcoded. The column names used here
 * (exchange, exchange_token, trading_symbol, groww_symbol, instrument_type, segment, underlying_symbol, expiry_date,
 * strike_price, lot_size, tick_size) are PARTLY UNVERIFIED (the SDK only proves the first four): if a required column is
 * missing the provider fails loudly and lists the headers found. Contracts with implausible metadata are marked
 * not tradable rather than guessed.
 */
public class GrowwInstrumentMaster implements InstrumentMetadataProvider {
    private static final Logger log = LoggerFactory.getLogger(GrowwInstrumentMaster.class);
    private static final Duration TTL = Duration.ofHours(6);

    private final AppProperties props;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Supplier<String> csvSupplier;
    private volatile Snapshot snapshot;

    private record Snapshot(Instant loadedAt, Map<String, Instrument> equities, Map<String, Instrument> indices,
                            Map<String, List<Instrument>> optionsByUnderlying) {}

    public GrowwInstrumentMaster(AppProperties props) { this(props, null); }

    /** @param csvSupplier test hook: supplies CSV text instead of downloading */
    public GrowwInstrumentMaster(AppProperties props, Supplier<String> csvSupplier) {
        this.props = props;
        this.csvSupplier = csvSupplier;
    }

    @Override public String sourceName() { return "GROWW_INSTRUMENT_MASTER"; }

    @Override public Optional<Instrument> findEquity(String symbol) { return Optional.ofNullable(snap().equities.get(symbol)); }

    @Override public Optional<Instrument> findIndex(String underlying) { return Optional.ofNullable(snap().indices.get(underlying)); }

    @Override public List<LocalDate> optionExpiries(String underlying, LocalDate from) {
        return snap().optionsByUnderlying.getOrDefault(underlying, List.of()).stream()
                .map(Instrument::expiry).filter(Objects::nonNull).filter(d -> !d.isBefore(from)).distinct().sorted().toList();
    }

    @Override public List<Instrument> optionContracts(String underlying, LocalDate expiry) {
        return snap().optionsByUnderlying.getOrDefault(underlying, List.of()).stream().filter(i -> expiry.equals(i.expiry())).toList();
    }

    private Snapshot snap() {
        Snapshot s = snapshot;
        if (s != null && Duration.between(s.loadedAt, Instant.now()).compareTo(TTL) < 0) return s;
        synchronized (this) {
            s = snapshot;
            if (s != null && Duration.between(s.loadedAt, Instant.now()).compareTo(TTL) < 0) return s;
            try {
                snapshot = parse(download());
            } catch (RuntimeException e) {
                if (snapshot == null) throw e;
                log.warn("instrument master refresh failed, using cached copy from {}: {}", snapshot.loadedAt, e.getMessage());
            }
            return snapshot;
        }
    }

    private String download() {
        if (csvSupplier != null) return csvSupplier.get();
        try {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(props.groww().instrumentCsvUrl())).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new ProviderException("instrument master download returned HTTP " + r.statusCode());
            return r.body();
        } catch (IOException e) {
            throw new ProviderException("instrument master download failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("instrument master download interrupted");
        }
    }

    Snapshot parse(String csv) {
        List<List<String>> rows = Csv.parse(csv);
        if (rows.size() < 2) throw new ProviderException("instrument master is empty");
        Map<String, Integer> col = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) col.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        for (String required : List.of("exchange", "trading_symbol", "instrument_type", "segment")) {
            if (!col.containsKey(required)) throw new ProviderException("instrument master missing column '" + required + "'; columns found: " + header);
        }
        Set<String> wantedUnderlyings = new HashSet<>(props.universe().indexUnderlyings());
        Set<String> wantedEquities = new HashSet<>(props.universe().equities());
        Map<String, Instrument> eq = new HashMap<>(), idx = new HashMap<>();
        Map<String, List<Instrument>> opts = new HashMap<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String exchange = get(row, col, "exchange");
            if (!"NSE".equalsIgnoreCase(exchange)) continue;
            String type = get(row, col, "instrument_type").toUpperCase(Locale.ROOT);
            String ts = get(row, col, "trading_symbol");
            String segment = get(row, col, "segment").toUpperCase(Locale.ROOT);
            String underlying = get(row, col, "underlying_symbol");
            if (type.equals("EQ") && wantedEquities.contains(ts)) {
                eq.putIfAbsent(ts, build(row, col, InstrumentType.EQUITY, null, "NSE", segment.isEmpty() ? "CASH" : segment, ts, underlying));
            } else if (type.equals("IDX") && wantedUnderlyings.contains(ts)) {
                idx.putIfAbsent(ts, build(row, col, InstrumentType.INDEX, null, "NSE", segment.isEmpty() ? "CASH" : segment, ts, ts));
            } else if ((type.equals("CE") || type.equals("PE")) && wantedUnderlyings.contains(underlying)) {
                Instrument i = build(row, col, InstrumentType.OPTION, OptionType.valueOf(type), "NSE", segment.isEmpty() ? "FNO" : segment, ts, underlying);
                opts.computeIfAbsent(underlying, k -> new ArrayList<>()).add(i);
            }
        }
        log.info("Groww instrument master loaded: {} equities, {} indices, {} option contracts", eq.size(), idx.size(), opts.values().stream().mapToInt(List::size).sum());
        return new Snapshot(Instant.now(), eq, idx, opts);
    }

    private Instrument build(List<String> row, Map<String, Integer> col, InstrumentType type, OptionType ot, String exchange,
                             String segment, String ts, String underlying) {
        BigDecimal tick = dec(get(row, col, "tick_size"));
        int lot = intVal(get(row, col, "lot_size"));
        LocalDate expiry = date(get(row, col, "expiry_date"));
        BigDecimal strike = dec(get(row, col, "strike_price"));
        boolean ok = tick != null && tick.signum() > 0 && tick.compareTo(BigDecimal.ONE) <= 0;     // >1 looks like paise/garbage
        if (type == InstrumentType.OPTION) ok = ok && lot > 0 && expiry != null && strike != null && strike.signum() > 0;
        if (type == InstrumentType.INDEX) ok = false;                                               // indices are never directly tradable
        if (tick == null || tick.signum() <= 0) tick = new BigDecimal("0.05");
        return new Instrument(null, exchange, segment, type, ts, nullIfBlank(get(row, col, "groww_symbol")), nullIfBlank(get(row, col, "name")),
                underlying == null || underlying.isBlank() ? null : underlying, expiry, strike, ot, Math.max(1, lot), tick,
                nullIfBlank(get(row, col, "exchange_token")), ok, "GROWW");
    }

    private static String get(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() ? "" : row.get(i).trim();
    }
    private static String nullIfBlank(String s) { return s == null || s.isBlank() ? null : s; }
    private static BigDecimal dec(String s) { try { return s == null || s.isBlank() ? null : new BigDecimal(s); } catch (NumberFormatException e) { return null; } }
    private static int intVal(String s) { try { return s == null || s.isBlank() ? 0 : new BigDecimal(s).intValue(); } catch (NumberFormatException e) { return 0; } }
    private static LocalDate date(String s) { try { return s == null || s.isBlank() ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); } catch (RuntimeException e) { return null; } }

    /** Minimal RFC-4180 CSV parser (quoted fields, escaped quotes, CRLF). */
    static final class Csv {
        static List<List<String>> parse(String text) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    if (c == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cur.append('"'); i++; } else quoted = false; }
                    else cur.append(c);
                } else if (c == '"') quoted = true;
                else if (c == ',') { row.add(cur.toString()); cur.setLength(0); }
                else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    row.add(cur.toString()); cur.setLength(0);
                    if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                    row = new ArrayList<>();
                } else cur.append(c);
            }
            if (cur.length() > 0 || !row.isEmpty()) { row.add(cur.toString()); rows.add(row); }
            return rows;
        }
    }
}
