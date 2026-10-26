package com.aiinvestor.groww;

import com.aiinvestor.broker.BrokerException;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.util.Redactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Minimal Groww REST transport. Request shape (base URL, Bearer auth, x-api-version, JSON envelope
 * {status, payload | error}) was taken from Groww's published `growwapi` Python SDK 1.5.0 source.
 * Never logs headers or bodies of authenticated calls. Failures map to {@link BrokerException} subclasses:
 *   401 → Auth, 403 → NotEntitled, 429 → RateLimited, 400 → Rejected,
 *   timeouts / 5xx / transport errors → Ambiguous for mutating calls (order may exist) or Unavailable for reads.
 */
public class GrowwHttp {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration timeout;
    private final long minIntervalNanos;
    private long nextSlotNanos = System.nanoTime();
    private final Supplier<String> tokenSupplier;
    private volatile Runnable onUnauthorized = () -> { };

    /** Invoked on any 401 from an authenticated call so the auth manager can pause broker-dependent operations. */
    public void onUnauthorized(Runnable r) { this.onUnauthorized = r; }

    public GrowwHttp(AppProperties.Groww cfg, ObjectMapper mapper, Supplier<String> tokenSupplier) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(cfg.requestTimeoutSeconds())).build();
        this.mapper = mapper;
        this.baseUrl = cfg.baseUrl().replaceAll("/+$", "");
        this.timeout = Duration.ofSeconds(cfg.requestTimeoutSeconds());
        this.minIntervalNanos = 1_000_000_000L / Math.max(1, cfg.maxRequestsPerSecond());
        this.tokenSupplier = tokenSupplier;
    }

    public JsonNode get(String path, Map<String, String> query) {
        return send(HttpRequest.newBuilder(uri(path, query)).GET(), false, true, tokenSupplier.get());
    }

    public JsonNode post(String path, Object body, boolean mutatesOrders) {
        return post(path, null, body, mutatesOrders, tokenSupplier.get());
    }

    /** Used by the auth manager, which supplies its own bearer (API key) instead of an access token. */
    public JsonNode post(String path, Map<String, String> query, Object body, boolean mutatesOrders, String bearer) {
        try {
            return send(HttpRequest.newBuilder(uri(path, query)).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))),
                    mutatesOrders, true, bearer);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BrokerException("could not serialise request");
        }
    }

    private URI uri(String path, Map<String, String> query) {
        StringBuilder sb = new StringBuilder(baseUrl).append(path.startsWith("/") ? path : "/" + path);
        if (query != null && !query.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (e.getValue() == null) continue;
                if (!first) sb.append('&');
                sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
        }
        return URI.create(sb.toString());
    }

    private JsonNode send(HttpRequest.Builder b, boolean mutating, boolean unwrap, String bearer) {
        acquireSlot();
        HttpRequest req = b.timeout(timeout)
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-request-id", UUID.randomUUID().toString())
                .header("x-client-id", "ai-investor-java")
                .header("x-client-platform", "ai-investor-java")
                .header("x-client-platform-version", "0.1.0")
                .header("x-api-version", "1.0")
                .build();
        HttpResponse<String> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw mutating ? new BrokerException.Ambiguous("Groww request timed out (outcome unknown)", e) : new BrokerException.Unavailable("Groww request timed out");
        } catch (IOException e) {
            throw mutating ? new BrokerException.Ambiguous("Groww transport error (outcome unknown)", e) : new BrokerException.Unavailable("Groww transport error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerException.Unavailable("interrupted");
        }
        if (resp.statusCode() == 401) onUnauthorized.run();
        return parse(resp.statusCode(), resp.body(), mutating);
    }

    JsonNode parse(int status, String body, boolean mutating) {
        JsonNode root = null;
        try { root = body == null || body.isBlank() ? null : mapper.readTree(body); } catch (IOException ignored) { /* handled below */ }
        String msg = errorMessage(root);
        if (status == 401) throw new BrokerException.Auth("Groww rejected the access token (401)" + msg);
        if (status == 403) throw new BrokerException.NotEntitled("Groww denied access (403) — missing permission or subscription" + msg);
        if (status == 429) throw new BrokerException.RateLimited("Groww rate limit exceeded (429)");
        if (status == 400 || status == 404 || status == 422) throw new BrokerException.Rejected("Groww rejected the request (" + status + ")" + msg);
        if (status >= 500 || status == 408) {
            throw mutating ? new BrokerException.Ambiguous("Groww server error " + status + " (outcome unknown)", null) : new BrokerException.Unavailable("Groww server error " + status);
        }
        if (status < 200 || status >= 300) throw new BrokerException("Groww returned HTTP " + status + msg);
        if (root == null) throw mutating ? new BrokerException.Ambiguous("Groww response unreadable (outcome unknown)", null) : new BrokerException.Unavailable("unreadable response");
        JsonNode st = root.get("status");
        if (st != null && "FAILURE".equalsIgnoreCase(st.asText())) throw new BrokerException.Rejected("Groww reported FAILURE" + msg);
        return root.has("payload") ? root.get("payload") : root;
    }

    private static String errorMessage(JsonNode root) {
        if (root == null) return "";
        JsonNode err = root.get("error");
        if (err == null) return "";
        String code = err.path("code").asText("");
        String message = err.path("message").asText(err.path("displayMessage").asText(""));
        return Redactor.redact(": " + code + " " + message);
    }

    private synchronized void acquireSlot() {
        long now = System.nanoTime();
        if (nextSlotNanos > now) {
            try { Thread.sleep((nextSlotNanos - now) / 1_000_000, (int) ((nextSlotNanos - now) % 1_000_000)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        nextSlotNanos = Math.max(now, nextSlotNanos) + minIntervalNanos;
    }
}
