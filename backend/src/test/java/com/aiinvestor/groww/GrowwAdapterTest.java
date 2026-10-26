package com.aiinvestor.groww;

import com.aiinvestor.broker.BrokerDtos.BrokerOrder;
import com.aiinvestor.broker.BrokerDtos.BrokerOrderAck;
import com.aiinvestor.broker.BrokerDtos.BrokerOrderRequest;
import com.aiinvestor.broker.BrokerException;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Candle;
import com.aiinvestor.domain.Enums.InstrumentType;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.domain.Quote;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.market.ProviderException;
import com.aiinvestor.support.TestProps;
import com.aiinvestor.util.Redactor;
import com.aiinvestor.util.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the Groww adapter against a local fake server: nothing here can reach the real Groww API. */
class GrowwAdapterTest {
    record Hit(String method, String pathAndQuery, Map<String, String> headers, String body) {}
    record Reply(int status, String body, long delayMs) {}

    private HttpServer server;
    private final List<Hit> hits = new CopyOnWriteArrayList<>();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MarketSession session = new MarketSession(TestProps.defaults().market());
    private AppProperties props;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        props = props(Map.of("app.groww.access-token", "TESTTOKEN-abc123"));
    }

    @AfterEach
    void stop() { server.stop(0); }

    private AppProperties props(Map<String, String> extra) {
        Map<String, String> m = new HashMap<>(extra);
        m.put("app.data.source", "GROWW");
        m.put("app.groww.enabled", "true");
        m.put("app.groww.base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        m.putIfAbsent("app.groww.request-timeout-seconds", "1");
        m.putIfAbsent("app.groww.max-requests-per-second", "100");
        return TestProps.with(m);
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> h = new HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
        String pq = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
        hits.add(new Hit(ex.getRequestMethod(), pq, h, body));
        Reply r = replies.get(ex.getRequestURI().getPath());
        if (r == null) r = new Reply(404, "{\"status\":\"FAILURE\",\"error\":{\"code\":\"GA003\",\"message\":\"not found\"}}", 0);
        if (r.delayMs() > 0) { try { Thread.sleep(r.delayMs()); } catch (InterruptedException ignored) { } }
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try { ex.sendResponseHeaders(r.status(), out.length); ex.getResponseBody().write(out); } catch (IOException ignored) { /* client gave up (timeout test) */ }
        ex.close();
    }

    private void reply(String path, int status, String body) { replies.put(path, new Reply(status, body, 0)); }

    private GrowwHttp http(AppProperties p, GrowwAuthManager auth) {
        GrowwHttp h = new GrowwHttp(p.groww(), mapper, auth::token);
        h.onUnauthorized(auth::markExpired);
        auth.attach(h);
        return h;
    }

    private Instrument reliance() {
        return new Instrument(1L, "NSE", "CASH", InstrumentType.EQUITY, "RELIANCE", "NSE-RELIANCE", "Reliance", null, null, null, null, 1, new BigDecimal("0.05"), "2885", true, "GROWW");
    }

    private static String ok(String payload) { return "{\"status\":\"SUCCESS\",\"payload\":" + payload + "}"; }

    // ------------------------------------------------------------------ market data

    @Test
    void quoteRequestMatchesTheDocumentedShapeAndIsParsed() {
        reply("/v1/live-data/quote", 200, ok("{\"last_price\":2900.5,\"volume\":12345,\"depth\":{\"buy\":[{\"price\":2900.4,\"quantity\":10}],\"sell\":[{\"price\":2900.6,\"quantity\":5}]}}"));
        GrowwAuthManager auth = new GrowwAuthManager(props.groww(), mapper);
        GrowwMarketData md = new GrowwMarketData(http(props, auth), auth, session);
        Quote q = md.getQuote(reliance());
        assertThat(q.last()).isEqualTo(2900.5);
        assertThat(q.bid()).isEqualTo(2900.4);
        assertThat(q.ask()).isEqualTo(2900.6);
        assertThat(q.volume()).isEqualTo(12345);
        assertThat(q.source()).isEqualTo("GROWW");
        Hit h = hits.get(0);
        assertThat(h.method()).isEqualTo("GET");
        assertThat(h.pathAndQuery()).startsWith("/v1/live-data/quote?");
        assertThat(new HashSet<>(Arrays.asList(h.pathAndQuery().substring(h.pathAndQuery().indexOf('?') + 1).split("&"))))
                .containsExactlyInAnyOrder("exchange=NSE", "segment=CASH", "trading_symbol=RELIANCE");
        assertThat(h.headers().get("authorization")).isEqualTo("Bearer TESTTOKEN-abc123");
        assertThat(h.headers().get("x-api-version")).isEqualTo("1.0");
        assertThat(h.headers().get("x-request-id")).isNotBlank();
    }

    @Test
    void historicalCandlesAreParsedDroppingInconsistentRowsAndSorted() {
        LocalDate day = LocalDate.of(2025, 3, 4);
        long t = session.openInstant(day).getEpochSecond();
        String candles = "[[" + (t + 60) + ",101,102,100,101.5,500],[" + t + ",100,101,99.5,100.5,400],[" + (t + 120) + ",10,5,20,15,1],[\"bad\",1,1,1,1,1]]";
        reply("/v1/historical/candles", 200, ok("{\"candles\":" + candles + "}"));
        GrowwAuthManager auth = new GrowwAuthManager(props.groww(), mapper);
        List<Candle> out = new GrowwMarketData(http(props, auth), auth, session).getCandles(reliance(), 1, session.openInstant(day), session.closeInstant(day));
        assertThat(out).hasSize(2);                                              // the inconsistent (high<low) and non-timestamp rows were dropped
        assertThat(out.get(0).start()).isBefore(out.get(1).start());
        String q = hits.get(0).pathAndQuery();
        assertThat(q).contains("groww_symbol=NSE-RELIANCE").contains("candle_interval=1minute").contains("segment=CASH").contains("exchange=NSE");
    }

    @Test
    void missingMarketDataEntitlementSurfacesAsAClearProviderError() {
        reply("/v1/live-data/quote", 403, "{\"status\":\"FAILURE\",\"error\":{\"code\":\"GA005\",\"message\":\"forbidden\"}}");
        GrowwAuthManager auth = new GrowwAuthManager(props.groww(), mapper);
        GrowwMarketData md = new GrowwMarketData(http(props, auth), auth, session);
        assertThatThrownBy(() -> md.getQuote(reliance())).isInstanceOf(ProviderException.class)
                .hasMessageContaining("entitlement").hasCauseInstanceOf(BrokerException.NotEntitled.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("TESTTOKEN"));
    }

    @Test
    void requestsAreRateLimited() {
        reply("/v1/live-data/quote", 200, ok("{\"last_price\":1.0}"));
        AppProperties slow = props(Map.of("app.groww.access-token", "t", "app.groww.max-requests-per-second", "5"));
        GrowwAuthManager auth = new GrowwAuthManager(slow.groww(), mapper);
        GrowwMarketData md = new GrowwMarketData(http(slow, auth), auth, session);
        long t0 = System.nanoTime();
        for (int i = 0; i < 6; i++) md.getQuote(reliance());
        assertThat((System.nanoTime() - t0) / 1_000_000).as("6 calls at 5 rps need >= ~1s").isGreaterThanOrEqualTo(900);
    }

    // ------------------------------------------------------------------ authentication

    @Test
    void anExpiredTokenPausesBrokerCallsWithoutFurtherNetworkTraffic() {
        reply("/v1/live-data/quote", 401, "{\"status\":\"FAILURE\",\"error\":{\"code\":\"GA001\",\"message\":\"token expired\"}}");
        GrowwAuthManager auth = new GrowwAuthManager(props.groww(), mapper);
        GrowwMarketData md = new GrowwMarketData(http(props, auth), auth, session);
        assertThat(auth.state()).isEqualTo(GrowwAuthManager.State.OK);
        assertThatThrownBy(() -> md.getQuote(reliance())).isInstanceOf(ProviderException.class).hasCauseInstanceOf(BrokerException.Auth.class);
        assertThat(auth.state()).isEqualTo(GrowwAuthManager.State.AUTH_EXPIRED);
        assertThat(md.isConnected()).isFalse();
        int before = hits.size();
        assertThatThrownBy(() -> md.getQuote(reliance())).isInstanceOf(ProviderException.class);
        assertThat(hits.size()).as("paused: no further requests are sent").isEqualTo(before);
    }

    @Test
    void totpFlowAuthenticatesWithTheApiKeyAndAGeneratedCode() throws Exception {
        reply("/v1/token/api/access", 200, "{\"token\":\"NEWTOKEN-xyz\"}");
        AppProperties p = props(Map.of("app.groww.api-key", "APIKEY-1", "app.groww.totp-secret", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"));
        GrowwAuthManager auth = new GrowwAuthManager(p.groww(), mapper);
        http(p, auth);
        assertThat(auth.token()).isEqualTo("NEWTOKEN-xyz");
        assertThat(auth.state()).isEqualTo(GrowwAuthManager.State.OK);
        Hit h = hits.get(0);
        assertThat(h.headers().get("authorization")).isEqualTo("Bearer APIKEY-1");
        JsonNode body = mapper.readTree(h.body());
        assertThat(body.get("key_type").asText()).isEqualTo("totp");
        assertThat(body.get("totp").asText()).matches("\\d{6}");
    }

    @Test
    void approvalFlowSendsASha256ChecksumOfSecretAndTimestamp() throws Exception {
        reply("/v1/token/api/access", 200, "{\"token\":\"T2\"}");
        AppProperties p = props(Map.of("app.groww.api-key", "APIKEY-2", "app.groww.api-secret", "S3CRET"));
        GrowwAuthManager auth = new GrowwAuthManager(p.groww(), mapper);
        http(p, auth);
        assertThat(auth.token()).isEqualTo("T2");
        JsonNode body = mapper.readTree(hits.get(0).body());
        assertThat(body.get("key_type").asText()).isEqualTo("approval");
        long ts = body.get("timestamp").asLong();
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("S3CRET" + ts).getBytes(StandardCharsets.UTF_8)));
        assertThat(body.get("checksum").asText()).isEqualTo(expected);
        assertThat(hits.get(0).body()).doesNotContain("S3CRET");                   // the secret itself is never transmitted
    }

    @Test
    void failedReauthorisationIsNotRetriedWithinTheCooldownAndNeverBypassed() {
        reply("/v1/token/api/access", 400, "{\"status\":\"FAILURE\",\"error\":{\"code\":\"GA002\",\"displayMessage\":\"bad totp\"}}");
        AppProperties p = props(Map.of("app.groww.api-key", "APIKEY-1", "app.groww.totp-secret", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"));
        GrowwAuthManager auth = new GrowwAuthManager(p.groww(), mapper);
        http(p, auth);
        assertThatThrownBy(auth::token).isInstanceOf(BrokerException.Auth.class);
        assertThat(auth.state()).isEqualTo(GrowwAuthManager.State.AUTH_FAILED);
        assertThatThrownBy(auth::token).isInstanceOf(BrokerException.Auth.class);
        assertThat(hits).as("only one token request inside the cooldown window").hasSize(1);
    }

    @Test
    void withoutAnyCredentialsEveryCallFailsFast() {
        AppProperties p = props(Map.of());
        GrowwAuthManager auth = new GrowwAuthManager(p.groww(), mapper);
        assertThat(auth.state()).isEqualTo(GrowwAuthManager.State.NOT_CONFIGURED);
        assertThatThrownBy(auth::token).isInstanceOf(BrokerException.Auth.class);
        assertThat(hits).isEmpty();
    }

    // ------------------------------------------------------------------ orders (fake server only)

    private BrokerOrderRequest order() {
        return new BrokerOrderRequest("AIREF0000000001", "NSE", "CASH", "RELIANCE", "BUY", "LIMIT", "MIS", "DAY", 10, new BigDecimal("2900.50"), null);
    }

    private GrowwOrders orders() {
        GrowwAuthManager auth = new GrowwAuthManager(props.groww(), mapper);
        return new GrowwOrders(http(props, auth));
    }

    @Test
    void orderCreateBodyFollowsTheDocumentedFields() throws Exception {
        reply("/v1/order/create", 200, ok("{\"groww_order_id\":\"GMK1\",\"order_status\":\"OPEN\",\"order_reference_id\":\"AIREF0000000001\",\"remark\":\"ok\"}"));
        BrokerOrderAck ack = orders().place(order());
        assertThat(ack.brokerOrderId()).isEqualTo("GMK1");
        JsonNode b = mapper.readTree(hits.get(0).body());
        assertThat(hits.get(0).method()).isEqualTo("POST");
        assertThat(b.get("trading_symbol").asText()).isEqualTo("RELIANCE");
        assertThat(b.get("transaction_type").asText()).isEqualTo("BUY");
        assertThat(b.get("order_type").asText()).isEqualTo("LIMIT");
        assertThat(b.get("product").asText()).isEqualTo("MIS");
        assertThat(b.get("validity").asText()).isEqualTo("DAY");
        assertThat(b.get("segment").asText()).isEqualTo("CASH");
        assertThat(b.get("exchange").asText()).isEqualTo("NSE");
        assertThat(b.get("quantity").asInt()).isEqualTo(10);
        assertThat(b.get("order_reference_id").asText()).isEqualTo("AIREF0000000001");
    }

    @Test
    void aBrokerRejectionIsADefinitiveFailure() {
        reply("/v1/order/create", 400, "{\"status\":\"FAILURE\",\"error\":{\"code\":\"GA004\",\"message\":\"insufficient margin\"}}");
        assertThatThrownBy(() -> orders().place(order())).isInstanceOf(BrokerException.Rejected.class).hasMessageContaining("insufficient margin");
    }

    @Test
    void aTimeoutOnOrderSubmissionIsAmbiguousAndNeverRetriedAutomatically() {
        replies.put("/v1/order/create", new Reply(200, ok("{\"groww_order_id\":\"LATE\"}"), 3000));
        assertThatThrownBy(() -> orders().place(order())).isInstanceOf(BrokerException.Ambiguous.class);
        long creates = hits.stream().filter(h -> h.pathAndQuery().startsWith("/v1/order/create")).count();
        assertThat(creates).as("exactly one submission attempt").isEqualTo(1);
    }

    @Test
    void serverErrorsOnOrdersAreAmbiguousButOnReadsAreMerelyUnavailable() {
        reply("/v1/order/create", 503, "{}");
        assertThatThrownBy(() -> orders().place(order())).isInstanceOf(BrokerException.Ambiguous.class);
        reply("/v1/order/status/GM1", 503, "{}");
        assertThatThrownBy(() -> orders().status("GM1", "CASH")).isInstanceOf(BrokerException.Unavailable.class);
    }

    @Test
    void rateLimitResponsesAreDistinguished() {
        reply("/v1/order/create", 429, "{}");
        assertThatThrownBy(() -> orders().place(order())).isInstanceOf(BrokerException.RateLimited.class);
    }

    @Test
    void statusByReferenceMapsUnknownReferencesToEmpty() {
        reply("/v1/order/status/reference/NOPE", 404, "{\"status\":\"FAILURE\",\"error\":{\"code\":\"GA003\",\"message\":\"no such order\"}}");
        assertThat(orders().statusByReference("NOPE", "CASH")).isEmpty();
        reply("/v1/order/status/reference/AIREF1", 200, ok("{\"groww_order_id\":\"G9\",\"order_status\":\"EXECUTED\",\"filled_quantity\":10,\"quantity\":10,\"order_reference_id\":\"AIREF1\"}"));
        Optional<BrokerOrder> o = orders().statusByReference("AIREF1", "CASH");
        assertThat(o).isPresent();
        assertThat(o.get().isTerminal()).isTrue();
        assertThat(o.get().filledQuantity()).isEqualTo(10);
    }

    // ------------------------------------------------------------------ redaction

    @Test
    void secretsNeverAppearInErrorMessages() {
        reply("/v1/live-data/quote", 401, "{\"status\":\"FAILURE\",\"error\":{\"code\":\"GA001\",\"message\":\"bad token Bearer abc.def.ghi api_key=SUPERSECRET\"}}");
        GrowwAuthManager auth = new GrowwAuthManager(props.groww(), mapper);
        GrowwMarketData md = new GrowwMarketData(http(props, auth), auth, session);
        assertThatThrownBy(() -> md.getQuote(reliance())).satisfies(e -> {
            assertThat(e.getMessage()).doesNotContain("SUPERSECRET").doesNotContain("abc.def.ghi").doesNotContain("TESTTOKEN");
            assertThat(String.valueOf(e.getCause().getMessage())).doesNotContain("SUPERSECRET").doesNotContain("TESTTOKEN");
        });
    }

    @Test
    void redactorScrubsCommonCredentialShapes() {
        assertThat(Redactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abcdefghijk.sig12345")).doesNotContain("eyJ");
        assertThat(Redactor.redact("api_key=ABCD1234 other")).doesNotContain("ABCD1234");
        assertThat(Redactor.redact("{\"password\":\"hunter2\"}")).doesNotContain("hunter2");
        assertThat(Redactor.redact("key sk-ant-api03-ABCDEFGHIJKL")).doesNotContain("ABCDEFGHIJKL");
        assertThat(Redactor.redact("plain market text")).isEqualTo("plain market text");
    }

    // ------------------------------------------------------------------ instrument master & TOTP

    private static final String CSV = """
            exchange,exchange_token,trading_symbol,groww_symbol,name,instrument_type,segment,underlying_symbol,expiry_date,strike_price,lot_size,tick_size
            NSE,2885,RELIANCE,NSE-RELIANCE,"Reliance Industries, Ltd",EQ,CASH,,,,1,0.05
            NSE,999,NIFTY,NSE-NIFTY,Nifty 50,IDX,CASH,,,,1,0.05
            NSE,5001,NIFTY25MAR24500CE,NSE-NIFTY-25Mar25-24500-CE,Nifty CE,CE,FNO,NIFTY,2025-03-27,24500,75,0.05
            NSE,5002,NIFTY25MAR24500PE,NSE-NIFTY-25Mar25-24500-PE,Nifty PE,PE,FNO,NIFTY,2025-03-27,24500,75,0.05
            NSE,5003,NIFTY25APR24500CE,NSE-NIFTY-24Apr25-24500-CE,Nifty CE,CE,FNO,NIFTY,2025-04-24,24500,75,0.05
            NSE,5004,NIFTY25MAR24550CE,NSE-NIFTY-25Mar25-24550-CE,Nifty CE bad tick,CE,FNO,NIFTY,2025-03-27,24550,75,5.0
            NSE,5005,NIFTY25MAR24600CE,NSE-NIFTY-25Mar25-24600-CE,Nifty CE no expiry,CE,FNO,NIFTY,,24600,75,0.05
            NSE,5006,OTHER25MAR100CE,NSE-OTHER,Other CE,CE,FNO,OTHER,2025-03-27,100,10,0.05
            BSE,1,SENSEX,BSE-SENSEX,Sensex,IDX,CASH,,,,1,0.05
            """;

    @Test
    void instrumentMasterIsReadFromTheCsvAndNeverHardcoded() {
        GrowwInstrumentMaster m = new GrowwInstrumentMaster(props, () -> CSV);
        Instrument rel = m.findEquity("RELIANCE").orElseThrow();
        assertThat(rel.name()).isEqualTo("Reliance Industries, Ltd");                         // quoted comma handled
        assertThat(rel.tradable()).isTrue();
        assertThat(m.findIndex("NIFTY").orElseThrow().tradable()).as("an index is never directly tradable").isFalse();
        assertThat(m.optionExpiries("NIFTY", LocalDate.of(2025, 3, 1))).containsExactly(LocalDate.of(2025, 3, 27), LocalDate.of(2025, 4, 24));
        assertThat(m.optionExpiries("NIFTY", LocalDate.of(2025, 3, 28))).containsExactly(LocalDate.of(2025, 4, 24));
        List<Instrument> march = m.optionContracts("NIFTY", LocalDate.of(2025, 3, 27));
        assertThat(march).extracting(Instrument::tradingSymbol).contains("NIFTY25MAR24500CE", "NIFTY25MAR24500PE");
        Instrument ce = march.stream().filter(i -> i.tradingSymbol().equals("NIFTY25MAR24500CE")).findFirst().orElseThrow();
        assertThat(ce.lotSize()).isEqualTo(75);
        assertThat(ce.strike()).isEqualByComparingTo("24500");
        assertThat(ce.tickSize()).isEqualByComparingTo("0.05");
        assertThat(ce.tradable()).isTrue();
        assertThat(march.stream().filter(i -> i.tradingSymbol().equals("NIFTY25MAR24550CE")).findFirst().orElseThrow().tradable()).as("implausible tick size => not tradable").isFalse();
        assertThat(march.stream().filter(i -> i.tradingSymbol().equals("NIFTY25MAR24600CE")).findFirst()).as("row without expiry is excluded from its expiry").isEmpty();
        assertThat(m.optionContracts("OTHER", LocalDate.of(2025, 3, 27))).isEmpty();             // only configured underlyings are loaded
        assertThat(m.findEquity("SENSEX")).isEmpty();                                            // BSE rows ignored
    }

    @Test
    void instrumentMasterWithUnexpectedColumnsFailsLoudly() {
        GrowwInstrumentMaster m = new GrowwInstrumentMaster(props, () -> "foo,bar\n1,2\n");
        assertThatThrownBy(() -> m.findEquity("RELIANCE")).isInstanceOf(ProviderException.class).hasMessageContaining("missing column").hasMessageContaining("foo");
    }

    @Test
    void totpMatchesTheRfc6238TestVector() {
        assertThat(Totp.code("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Instant.ofEpochSecond(59))).isEqualTo("287082");
        assertThat(Totp.code("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Instant.ofEpochSecond(1111111109))).isEqualTo("081804");
    }
}
