package com.aiinvestor.runtime;

import java.time.Duration;
import java.util.Optional;

/** Short-lived shared state: cached status values and cross-instance locks. Redis-backed with an in-memory fallback. */
public interface TransientStore {
    void put(String key, String value, Duration ttl);
    Optional<String> get(String key);
    /** Non-blocking lock; returns a token to pass to {@link #unlock}, or empty if held elsewhere. */
    Optional<String> tryLock(String name, Duration ttl);
    void unlock(String name, String token);
    String backend();
}
