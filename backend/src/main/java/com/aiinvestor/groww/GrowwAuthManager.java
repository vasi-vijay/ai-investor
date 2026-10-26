package com.aiinvestor.groww;

import com.aiinvestor.broker.BrokerException;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.util.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the Groww access token and the authorisation state. Supported inputs (all from the environment / secret files,
 * never from the frontend or a chat):
 *   1. GROWW_ACCESS_TOKEN (optionally re-read from GROWW_ACCESS_TOKEN_FILE),
 *   2. GROWW_API_KEY + GROWW_TOTP_SECRET  → POST /token/api/access {key_type: totp, totp: <code>}
 *   3. GROWW_API_KEY + GROWW_API_SECRET   → POST /token/api/access {key_type: approval, checksum: sha256(secret+ts), timestamp}
 * On a 401 the state becomes AUTH_EXPIRED and every broker-dependent call fails fast (operations pause). Re-authorisation
 * only uses the same documented flows, rate-limited by a cooldown; it never bypasses authentication.
 */
public class GrowwAuthManager {
    public enum State { NOT_CONFIGURED, DISCONNECTED, OK, AUTH_EXPIRED, AUTH_FAILED }

    private static final Logger log = LoggerFactory.getLogger(GrowwAuthManager.class);
    private static final Duration REAUTH_COOLDOWN = Duration.ofMinutes(5);

    private final AppProperties.Groww cfg;
    private final ObjectMapper mapper;
    private final AtomicReference<String> token = new AtomicReference<>();
    private volatile State state = State.NOT_CONFIGURED;
    private volatile Instant lastAuthAttempt = Instant.EPOCH;
    private volatile String lastMessage = "not configured";
    private volatile boolean authenticating;
    private volatile Instant lastSuccess;
    private GrowwHttp http;

    public GrowwAuthManager(AppProperties.Groww cfg, ObjectMapper mapper) {
        this.cfg = cfg;
        this.mapper = mapper;
        loadInitialToken();
    }

    void attach(GrowwHttp http) { this.http = http; }

    private void loadInitialToken() {
        String t = readTokenFile();
        if (t == null && notBlank(cfg.accessToken())) t = cfg.accessToken().trim();
        if (t != null) { token.set(t); state = State.OK; lastMessage = "access token loaded"; lastSuccess = Instant.now(); }
        else if (canAutoAuth()) { state = State.AUTH_EXPIRED; lastMessage = "no token yet; will authenticate via configured key"; }
    }

    private boolean canAutoAuth() { return notBlank(cfg.apiKey()) && (notBlank(cfg.totpSecret()) || notBlank(cfg.apiSecret())); }

    private String readTokenFile() {
        if (!notBlank(cfg.accessTokenFile())) return null;
        try {
            String s = Files.readString(Path.of(cfg.accessTokenFile())).trim();
            return s.isEmpty() ? null : s;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Token for an API call, or throws Auth when operations are paused. Attempts documented re-auth at most once per cooldown. */
    public String token() {
        if (state == State.NOT_CONFIGURED) throw new BrokerException.Auth("Groww credentials are not configured");
        if (state == State.DISCONNECTED) throw new BrokerException.Auth("Groww is disconnected by the operator — use Connect on the Broker Connection page");
        if (state == State.OK && token.get() != null) return token.get();
        tryReauthorise();
        if (state == State.OK && token.get() != null) return token.get();
        throw new BrokerException.Auth("Groww authorisation is " + state + " — broker-dependent operations are paused. " + lastMessage);
    }

    /** Called by adapters when the API answers 401. */
    public void markExpired() {
        if (state == State.OK) log.warn("Groww access token rejected (401); pausing broker-dependent operations");
        state = State.AUTH_EXPIRED;
        lastMessage = "token expired or revoked";
        token.set(null);
    }

    /** Operator-initiated connect/reauthorise: ignores the automatic cooldown but still uses only the documented flows. */
    public synchronized State reauthorizeNow() {
        if (state == State.DISCONNECTED) state = State.AUTH_EXPIRED;
        lastAuthAttempt = Instant.EPOCH;
        if (state == State.NOT_CONFIGURED) {
            String t = readTokenFile();
            if (t == null && notBlank(cfg.accessToken())) t = cfg.accessToken().trim();
            if (t != null) { token.set(t); state = State.OK; lastMessage = "access token loaded"; lastSuccess = Instant.now(); }
            else if (canAutoAuth()) state = State.AUTH_EXPIRED;
        }
        if (state != State.OK && state != State.NOT_CONFIGURED) tryReauthorise();
        return state;
    }

    /** Operator-initiated disconnect: drops the in-memory token and stops automatic re-authorisation until reauthorizeNow(). */
    public synchronized void disconnect() {
        token.set(null);
        state = State.DISCONNECTED;
        lastMessage = "disconnected by operator";
    }

    public boolean authenticating() { return authenticating; }
    public Instant lastSuccess() { return lastSuccess; }
    public boolean credentialsPresent() { return notBlank(cfg.accessToken()) || notBlank(cfg.accessTokenFile()) || canAutoAuth(); }

    private synchronized void tryReauthorise() {
        if (state == State.DISCONNECTED) return;
        if (Duration.between(lastAuthAttempt, Instant.now()).compareTo(REAUTH_COOLDOWN) < 0) return;
        lastAuthAttempt = Instant.now();
        String fromFile = readTokenFile();
        if (fromFile != null) { token.set(fromFile); state = State.OK; lastMessage = "token re-read from file"; return; }
        if (!canAutoAuth() || http == null) { lastMessage = "operator must supply a fresh token (GROWW_ACCESS_TOKEN / token file)"; return; }
        authenticating = true;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            if (notBlank(cfg.totpSecret())) {
                body.put("key_type", "totp");
                body.put("totp", Totp.code(cfg.totpSecret(), Instant.now()));
            } else {
                long ts = Instant.now().getEpochSecond();
                body.put("key_type", "approval");
                body.put("checksum", sha256(cfg.apiSecret() + ts));
                body.put("timestamp", ts);
            }
            JsonNode resp = http.post("/token/api/access", null, body, false, cfg.apiKey());
            JsonNode t = resp.get("token");
            if (t == null || t.asText().isBlank()) throw new BrokerException.Auth("token missing in response");
            token.set(t.asText());
            state = State.OK;
            lastSuccess = Instant.now();
            lastMessage = "re-authorised via documented token flow";
            log.info("Groww access token refreshed via configured API key flow");
        } catch (BrokerException e) {
            state = State.AUTH_FAILED;
            lastMessage = "re-authorisation failed: " + e.getMessage();
            log.warn("Groww re-authorisation failed: {}", e.getMessage());
        } finally {
            authenticating = false;
        }
    }

    public State state() { return state; }
    public String message() { return lastMessage; }

    private static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
