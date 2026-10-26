package com.aiinvestor.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTransientStore implements TransientStore {
    private record Entry(String value, Instant expires) {}
    private final Map<String, Entry> map = new ConcurrentHashMap<>();

    @Override public void put(String key, String value, Duration ttl) { map.put(key, new Entry(value, Instant.now().plus(ttl))); }

    @Override public Optional<String> get(String key) {
        Entry e = map.get(key);
        if (e == null || e.expires().isBefore(Instant.now())) { map.remove(key); return Optional.empty(); }
        return Optional.of(e.value());
    }

    @Override public Optional<String> tryLock(String name, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Entry mine = new Entry(token, now.plus(ttl));
        Entry prev = map.compute("lock:" + name, (k, cur) -> cur == null || cur.expires().isBefore(now) ? mine : cur);
        return prev == mine ? Optional.of(token) : Optional.empty();
    }

    @Override public void unlock(String name, String token) {
        map.computeIfPresent("lock:" + name, (k, cur) -> cur.value().equals(token) ? null : cur);
    }

    @Override public String backend() { return "memory"; }
}
