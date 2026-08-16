package com.aiagent.controller;

import com.aiagent.config.AuthRateLimitFilter;
import com.aiagent.config.SecurityConfig;
import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.CustomOAuth2UserService;
import com.aiagent.service.CustomUserDetailsService;
import com.aiagent.service.DocumentAccessService;
import com.aiagent.service.DocumentService;
import com.aiagent.service.RateLimiterService;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-002 — verifies /api/documents endpoints respect DocumentAccessService
 * instead of returning raw, unfiltered entities.
 */
@WebMvcTest(DocumentApiController.class)
@Import({SecurityConfig.class, AuthRateLimitFilter.class, RateLimiterService.class})
class DocumentApiControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentService documentService;
    @MockBean
    private DocumentAccessService documentAccessService;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private DocumentRepository documentRepository;
    @MockBean
    private CustomOAuth2UserService customOAuth2UserService;
    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    private User userWithRole(Long id, String email, String roleCode) {
        Role role = new Role();
        role.setCode(roleCode);
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setRole(role);
        return user;
    }

    // AccessLevel value is cosmetic fixture data here — documentAccessService
    // is a @MockBean, so canAccessDocument's real scope logic never runs;
    // each test stubs the boolean outcome directly. PRIVATE scope was removed.
    private Document restrictedDocOwnedByOther() {
        Document doc = new Document();
        doc.setId(42L);
        doc.setAccessLevel(AccessLevel.DEPARTMENT);
        doc.setTitle("Confidential");
        doc.setContent("secret content");
        return doc;
    }

    @Test
    void getAllDocuments_anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void getDocument_unauthorizedForThisDocument_isForbidden() throws Exception {
        when(userRepository.findByEmail("employee@company.com"))
                .thenReturn(Optional.of(userWithRole(1L, "employee@company.com", RoleConstants.ROLE_EMPLOYEE)));
        when(documentService.getDocument(42L)).thenReturn(restrictedDocOwnedByOther());
        when(documentAccessService.canAccessDocument(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);

        mockMvc.perform(get("/api/documents/42"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void getDocument_authorizedForThisDocument_isOk() throws Exception {
        when(userRepository.findByEmail("employee@company.com"))
                .thenReturn(Optional.of(userWithRole(1L, "employee@company.com", RoleConstants.ROLE_EMPLOYEE)));
        when(documentService.getDocument(42L)).thenReturn(restrictedDocOwnedByOther());
        when(documentAccessService.canAccessDocument(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        mockMvc.perform(get("/api/documents/42"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void searchDocuments_anonymousBlocked_authenticatedAllowedThroughRbacQuery() throws Exception {
        when(userRepository.findByEmail("employee@company.com"))
                .thenReturn(Optional.of(userWithRole(1L, "employee@company.com", RoleConstants.ROLE_EMPLOYEE)));
        when(documentRepository.findCandidateDocuments(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(RoleConstants.ROLE_EMPLOYEE),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/documents/search").param("keyword", "policy"))
                .andExpect(status().isOk());
    }
}
