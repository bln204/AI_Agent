package com.aiagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SEC-009 — verifies actuator exposure is restricted at the real HTTP layer.
 * Overall health status stays public (required for Docker/orchestrator
 * liveness & readiness probes), but component-level detail (DB, disk,
 * Qdrant, etc.) requires an authenticated ADMIN/DIRECTOR, and sensitive
 * endpoints (env, beans, configprops, mappings, ...) are never registered
 * regardless of authentication, per management.endpoints.web.exposure.include.
 *
 * Only checks HTTP-layer exposure, never RAG retrieval, so the real
 * ONNX embedding model is replaced with a Mockito mock via
 * {@code @MockBean} to avoid the memory-heavy native load.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorSecurityTest {

    private static final String[] UNREGISTERED_ENDPOINTS = {
            "env", "beans", "configprops", "mappings", "loggers", "threaddump", "heapdump", "scheduledtasks"
    };

    @MockBean(name = "embeddingModel")
    private EmbeddingModel embeddingModel;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health_anonymous_isReachable_butHidesComponentDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @WithMockUser(username = "employee@company.com", authorities = "ROLE_EMPLOYEE")
    void health_authenticatedNonPrivilegedUser_stillHidesComponentDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @WithMockUser(username = "director@company.com", authorities = "ROLE_DIRECTOR")
    void health_authenticatedDirector_revealsComponentDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").exists());
    }

    @Test
    void unregisteredActuatorEndpoints_anonymous_areNeverAccessible() throws Exception {
        for (String endpoint : UNREGISTERED_ENDPOINTS) {
            mockMvc.perform(get("/actuator/" + endpoint))
                    .andExpect(status().is3xxRedirection());
        }
    }

    @Test
    @WithMockUser(username = "admin@company.com", authorities = "ROLE_ADMIN")
    void unregisteredActuatorEndpoints_evenForAdmin_returnNotFound() throws Exception {
        // Proves these endpoints are genuinely unregistered (exposure.include=health,info),
        // not merely permission-gated — an ADMIN still gets 404, not data.
        for (String endpoint : UNREGISTERED_ENDPOINTS) {
            mockMvc.perform(get("/actuator/" + endpoint))
                    .andExpect(status().isNotFound());
        }
    }
}
