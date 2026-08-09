package com.aiagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * SEC-007 — the generic exception handler must never leak the raw exception
 * message (which can contain DB URLs, file paths, or other internal detail)
 * or a stack trace back to the HTTP client; only a fixed, safe message.
 */
class AiGlobalExceptionHandlerSecurityTest {

    private final AiGlobalExceptionHandler handler = new AiGlobalExceptionHandler();

    @Test
    void genericException_response_neverLeaksRawMessageOrStackTrace() {
        Exception secretLeakingException = new RuntimeException(
                "Connection failed: jdbc:mysql://root:S3cretDbPassw0rd@10.0.0.5:3306/ai_agent");

        ResponseEntity<Map<String, String>> response = handler.handleGenericException(secretLeakingException);

        String body = response.getBody().toString();
        assertFalse(body.contains("S3cretDbPassw0rd"), "response body must never contain leaked credentials");
        assertFalse(body.contains("jdbc:mysql"), "response body must never contain internal connection strings");
        assertEquals(500, response.getStatusCode().value());
    }
}
