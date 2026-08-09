package com.aiagent.controller;

import com.aiagent.config.AuthRateLimitFilter;
import com.aiagent.config.SecurityConfig;
import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.ProjectMemberRepository;
import com.aiagent.repository.ProjectRepository;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-002 — /documents/{id}/download must run canAccessDocument() before
 * reading the physical file (previously this endpoint had no check at all).
 */
@WebMvcTest(DocumentController.class)
@Import({SecurityConfig.class, AuthRateLimitFilter.class, RateLimiterService.class})
class DocumentControllerDownloadSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentService documentService;
    @MockBean
    private DocumentAccessService documentAccessService;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private DepartmentRepository departmentRepository;
    @MockBean
    private ProjectMemberRepository projectMemberRepository;
    @MockBean
    private ProjectRepository projectRepository;
    @MockBean
    private CustomOAuth2UserService customOAuth2UserService;
    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    private User employee() {
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_EMPLOYEE);
        User user = new User();
        user.setId(1L);
        user.setEmail("employee@company.com");
        user.setRole(role);
        return user;
    }

    private Document privateDocOwnedByOther() {
        Document doc = new Document();
        doc.setId(42L);
        doc.setAccessLevel(AccessLevel.PRIVATE);
        doc.setFilePath("uploads/does-not-matter.txt");
        return doc;
    }

    @Test
    void download_anonymous_isRedirectedToLogin() throws Exception {
        // /documents/** is a browser page route (not /api/**), so unauthenticated
        // access follows the same redirect-to-login behavior as the rest of the app.
        mockMvc.perform(get("/documents/42/download"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void download_unauthorizedForThisDocument_isForbidden_fileNeverRead() throws Exception {
        when(userRepository.findByEmail("employee@company.com")).thenReturn(Optional.of(employee()));
        when(documentService.getDocument(42L)).thenReturn(privateDocOwnedByOther());
        when(documentAccessService.canAccessDocument(any(), any())).thenReturn(false);

        mockMvc.perform(get("/documents/42/download"))
                .andExpect(status().isForbidden());
    }
}
