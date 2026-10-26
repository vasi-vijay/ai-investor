package com.aiinvestor.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis implementation (SET NX PX locks, TTL values). If Redis is unreachable it degrades to the in-memory store for
 * that call — acceptable for the single-instance pilot, and logged so operators notice.
 */
public class RedisTransientStore implements TransientStore {
    private static final Logger log = LoggerFactory.getLogger(RedisTransientStore.class);
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final InMemoryTransientStore fallback = new InMemoryTransientStore();
    private volatile boolean degraded;

    public RedisTransientStore(StringRedisTemplate redis) { this.redis = redis; }

    private void degrade(RuntimeException e) {
        if (!degraded) log.warn("Redis unavailable, using in-memory transient state ({})", e.getClass().getSimpleName());
        degraded = true;
    }

    @Override public void put(String key, String value, Duration ttl) {
        try { redis.opsForValue().set("ai-investor:" + key, value, ttl); degraded = false; }
        catch (RuntimeException e) { degrade(e); fallback.put(key, value, ttl); }
    }

    @Override public Optional<String> get(String key) {
        try { return Optional.ofNullable(redis.opsForValue().get("ai-investor:" + key)); }
        catch (RuntimeException e) { degrade(e); return fallback.get(key); }
    }

    @Override public Optional<String> tryLock(String name, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean ok = redis.opsForValue().setIfAbsent("ai-investor:lock:" + name, token, ttl);
            return Boolean.TRUE.equals(ok) ? Optional.of(token) : Optional.empty();
        } catch (RuntimeException e) { degrade(e); return fallback.tryLock(name, ttl); }
    }

    @Override public void unlock(String name, String token) {
        try { redis.execute(UNLOCK, List.of("ai-investor:lock:" + name), token); }
        catch (RuntimeException e) { degrade(e); fallback.unlock(name, token); }
    }

    @Override public String backend() { return degraded ? "redis(degraded->memory)" : "redis"; }
}
