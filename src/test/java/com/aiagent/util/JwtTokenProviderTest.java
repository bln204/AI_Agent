package com.aiagent.util;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SEC-005 — JwtTokenProvider must fail fast when jwt.secret is not
 * configured, instead of silently falling back to a hard-coded, predictable
 * default secret that would let anyone forge valid tokens.
 */
class JwtTokenProviderTest {

    @Test
    void missingSecret_failsFastAtStartup_insteadOfUsingHardcodedDefault() {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", "");
        ReflectionTestUtils.setField(provider, "jwtExpiration", 86400000L);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(provider, "validateSecret"));
        assertTrue(ex.getMessage().contains("jwt.secret"));
        assertFalse(ex.getMessage().toLowerCase().contains("mysecretkeyforjwt"),
                "error message must not itself leak/reference the old hardcoded default");
    }

    @Test
    void blankSecret_failsFast() {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", "   ");

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(provider, "validateSecret"));
    }

    @Test
    void configuredSecret_startsCleanly_andProvidesWorkingTokenRoundTrip() {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", "a-properly-configured-test-secret-of-sufficient-length-1234567890");
        ReflectionTestUtils.setField(provider, "jwtExpiration", 86400000L);

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(provider, "validateSecret"));

        String token = provider.generateToken("user@company.com", 42L);
        assertNotNull(token);
        assertTrue(provider.validateToken(token));
        assertEquals("user@company.com", provider.getEmailFromToken(token));
        assertEquals(42L, provider.getUserIdFromToken(token));
    }

    @Test
    void tokenSignedWithDifferentSecret_isRejected() {
        JwtTokenProvider providerA = new JwtTokenProvider();
        ReflectionTestUtils.setField(providerA, "jwtSecret", "secret-a-1234567890-1234567890-1234567890");
        ReflectionTestUtils.setField(providerA, "jwtExpiration", 86400000L);
        String token = providerA.generateToken("user@company.com", 1L);

        JwtTokenProvider providerB = new JwtTokenProvider();
        ReflectionTestUtils.setField(providerB, "jwtSecret", "secret-b-different-1234567890-1234567890");
        ReflectionTestUtils.setField(providerB, "jwtExpiration", 86400000L);

        assertFalse(providerB.validateToken(token));
    }
}
