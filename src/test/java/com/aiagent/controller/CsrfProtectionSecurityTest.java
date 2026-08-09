package com.aiagent.controller;

import com.aiagent.config.AuthRateLimitFilter;
import com.aiagent.config.SecurityConfig;
import com.aiagent.dto.AuthResponse;
import com.aiagent.rag.DocumentIngestionService;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.AuthService;
import com.aiagent.service.CustomOAuth2UserService;
import com.aiagent.service.CustomUserDetailsService;
import com.aiagent.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-003 — /api/** must require a valid CSRF token again now that the
 * blanket "/api/**" exemption has been removed; /auth/** stays exempt
 * (pre-session, nothing to protect yet).
 */
@WebMvcTest({MaintenanceController.class, AuthApiController.class})
@Import({SecurityConfig.class, AuthRateLimitFilter.class, RateLimiterService.class})
class CsrfProtectionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentRepository documentRepository;
    @MockBean
    private DocumentIngestionService ingestionService;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private AuthService authService;
    @MockBean
    private CustomOAuth2UserService customOAuth2UserService;
    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    @WithMockUser(username = "director@company.com", authorities = "ROLE_DIRECTOR")
    void apiPost_withoutCsrfToken_isForbidden() throws Exception {
        mockMvc.perform(post("/api/maintenance/reindex"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "director@company.com", authorities = "ROLE_DIRECTOR")
    void apiPost_withCsrfToken_isAccepted() throws Exception {
        when(documentRepository.findAll()).thenReturn(java.util.List.of());

        mockMvc.perform(post("/api/maintenance/reindex").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void authRegister_withoutCsrfToken_isNotBlockedByCsrfFilter() throws Exception {
        // /auth/** stays CSRF-exempt (pre-session, nothing to protect yet).
        // A missing/invalid request body would fail validation elsewhere, but
        // the CSRF filter itself must never be the reason for a 403 here.
        when(authService.register(any())).thenReturn(AuthResponse.builder().success(true).build());

        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"u\",\"email\":\"u@x.com\",\"password\":\"pw\"}"))
                .andExpect(status().isCreated());
    }
}
