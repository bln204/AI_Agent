package com.aiagent.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SEC-011 — in-memory brute-force / spam throttling for the authentication
 * endpoints (/login/form, /auth/login, /auth/register).
 *
 * In-memory only: counters are per-instance and are NOT shared across a
 * horizontally-scaled (multi-instance) deployment. The current
 * docker-compose.yml runs exactly one `app` container, so this is
 * acceptable for now; if the app is ever scaled out behind a load
 * balancer, this must move to a shared store (e.g. Redis) or the limit
 * becomes bypassable by round-robining across instances.
 *
 * Bounded memory: each cache caps at 50,000 entries with a fixed
 * expiry, so worst-case footprint is on the order of a few MB
 * (short String key + one AtomicInteger per entry) — not user input
 * held indefinitely, and self-evicting via Caffeine's expireAfterWrite.
 */
@Service
public class RateLimiterService {

    private static final int MAX_LOGIN_FAILURES = 5;
    private static final int MAX_REGISTRATIONS_PER_IP = 10;

    private final Cache<String, AtomicInteger> loginFailures = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15))
            .maximumSize(50_000)
            .build();

    private final Cache<String, AtomicInteger> registrationAttempts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(60))
            .maximumSize(50_000)
            .build();

    public boolean isLoginBlocked(String key) {
        AtomicInteger count = loginFailures.getIfPresent(key);
        return count != null && count.get() >= MAX_LOGIN_FAILURES;
    }

    public void recordLoginFailure(String key) {
        loginFailures.get(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public void recordLoginSuccess(String key) {
        loginFailures.invalidate(key);
    }

    public boolean isRegistrationBlocked(String ip) {
        AtomicInteger count = registrationAttempts.getIfPresent(ip);
        return count != null && count.get() >= MAX_REGISTRATIONS_PER_IP;
    }

    public void recordRegistrationAttempt(String ip) {
        registrationAttempts.get(ip, k -> new AtomicInteger(0)).incrementAndGet();
    }
}
