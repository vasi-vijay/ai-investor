package com.aiinvestor.security;

import com.aiinvestor.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-client token bucket (client = remote address). Login attempts get a much smaller budget to slow brute force. */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final class Bucket {
        double tokens; long lastNanos;
        Bucket(double t) { tokens = t; lastNanos = System.nanoTime(); }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int apiPerMinute;

    public RateLimitFilter(AppProperties props) { this.apiPerMinute = props.security().apiRequestsPerMinute(); }

    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/")) { chain.doFilter(req, res); return; }
        boolean login = path.equals("/api/auth/login");
        int perMinute = login ? 10 : apiPerMinute;
        String key = (login ? "login:" : "api:") + req.getRemoteAddr();
        if (!allow(key, perMinute)) {
            res.setStatus(429);
            res.setHeader("Retry-After", "30");
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"rate_limited\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    public boolean allow(String key, int perMinute) {
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(perMinute));
        synchronized (b) {
            long now = System.nanoTime();
            b.tokens = Math.min(perMinute, b.tokens + (now - b.lastNanos) / 60e9 * perMinute);
            b.lastNanos = now;
            if (b.tokens < 1) return false;
            b.tokens -= 1;
            return true;
        }
    }
}
