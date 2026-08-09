package com.aiagent.controller;

import com.aiagent.config.AuthRateLimitFilter;
import com.aiagent.config.SecurityConfig;
import com.aiagent.rag.DocumentIngestionService;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.UserRepository;
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

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-001 — verifies @PreAuthorize("hasRole('DIRECTOR')") on the maintenance
 * endpoint is actually enforced now that @EnableMethodSecurity is active.
 * Exercised through real HTTP + the real security filter chain, not a
 * direct Java method call, per the remediation spec's requirement.
 */
@WebMvcTest(MaintenanceController.class)
@Import({SecurityConfig.class, AuthRateLimitFilter.class, RateLimiterService.class})
class MaintenanceControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentRepository documentRepository;
    @MockBean
    private DocumentIngestionService ingestionService;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private CustomOAuth2UserService customOAuth2UserService;
    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void reindex_anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(post("/api/maintenance/reindex").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "employee@company.com", authorities = "ROLE_EMPLOYEE")
    void reindex_employee_isForbidden() throws Exception {
        mockMvc.perform(post("/api/maintenance/reindex").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "manager@company.com", authorities = "ROLE_MANAGER")
    void reindex_manager_isForbidden() throws Exception {
        mockMvc.perform(post("/api/maintenance/reindex").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "director@company.com", authorities = "ROLE_DIRECTOR")
    void reindex_director_isOk() throws Exception {
        when(documentRepository.findAll()).thenReturn(java.util.List.of());

        mockMvc.perform(post("/api/maintenance/reindex").with(csrf()))
                .andExpect(status().isOk());
    }
}
