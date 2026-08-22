package com.aiagent.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SEC-011 — in-memory brute-force / spam throttling for the authentication
 * endpoints (/login/form, /auth/login).
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
    private static final int MAX_OTP_VERIFY_FAILURES = 5;
    private static final int OTP_REQUEST_COOLDOWN_SECONDS = 60;

    private final Cache<String, AtomicInteger> loginFailures = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15))
            .maximumSize(50_000)
            .build();

    // SEC — brute-force guard cho /forgot-password/verify-otp, cùng ngưỡng và
    // cửa sổ với loginFailures nhưng cache riêng để không lẫn 2 luồng khác nhau.
    private final Cache<String, AtomicInteger> otpVerifyFailures = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15))
            .maximumSize(50_000)
            .build();

    // Chống spam gửi email OTP liên tục cho cùng 1 email/IP.
    private final Cache<String, Boolean> otpRequestCooldown = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(OTP_REQUEST_COOLDOWN_SECONDS))
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

    public boolean isOtpVerifyBlocked(String key) {
        AtomicInteger count = otpVerifyFailures.getIfPresent(key);
        return count != null && count.get() >= MAX_OTP_VERIFY_FAILURES;
    }

    public void recordOtpVerifyFailure(String key) {
        otpVerifyFailures.get(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public void recordOtpVerifySuccess(String key) {
        otpVerifyFailures.invalidate(key);
    }

    public boolean isOtpRequestOnCooldown(String key) {
        return otpRequestCooldown.getIfPresent(key) != null;
    }

    public void markOtpRequested(String key) {
        otpRequestCooldown.put(key, Boolean.TRUE);
    }
}
