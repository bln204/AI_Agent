package com.aiagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-007 — the password hash must never be present in any JSON response
 * (e.g. UserController's ResponseEntity<User>/ResponseEntity<List<User>>
 * endpoints). Guards the @JsonIgnore on User.password against regression.
 */
class UserPasswordSerializationTest {

    @Test
    void user_serializedToJson_neverIncludesPassword() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("director");
        user.setEmail("director@company.com");
        user.setPassword("$2a$10$superSecretBcryptHashValue");
        user.setStatus("ACTIVE");

        String json = new ObjectMapper().writeValueAsString(user);

        assertFalse(json.contains("password"), "serialized User must not contain a 'password' field");
        assertFalse(json.contains("superSecretBcryptHashValue"), "serialized User must not leak the password hash value");
        assertTrue(json.contains("director@company.com"), "non-sensitive fields should still serialize normally");
    }
}
