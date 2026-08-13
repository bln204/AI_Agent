package com.aiagent.controller;

import com.aiagent.config.AuthRateLimitFilter;
import com.aiagent.config.SecurityConfig;
import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.model.ViewerStatus;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.CustomOAuth2UserService;
import com.aiagent.service.CustomUserDetailsService;
import com.aiagent.service.DocumentAccessService;
import com.aiagent.service.DocumentService;
import com.aiagent.service.RateLimiterService;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/documents/{id}/viewer phải tuân thủ đúng checklist bảo mật:
 * Authenticate -> Authorize (canAccessDocument, giống /documents/{id}/download)
 * -> document tồn tại -> viewer file tồn tại -> stream application/pdf.
 * Không được đọc file vật lý trước khi authorization pass (chống IDOR).
 */
@WebMvcTest(DocumentApiController.class)
@Import({SecurityConfig.class, AuthRateLimitFilter.class, RateLimiterService.class})
class DocumentApiControllerViewerSecurityTest {

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

    @TempDir
    Path tempDir;

    private User employee() {
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_EMPLOYEE);
        User user = new User();
        user.setId(1L);
        user.setEmail("employee@company.com");
        user.setRole(role);
        return user;
    }

    private Document privateDocOwnedByOther(String viewerFilePath) {
        Document doc = new Document();
        doc.setId(42L);
        doc.setAccessLevel(AccessLevel.PRIVATE);
        doc.setFileType("PDF");
        doc.setViewerFilePath(viewerFilePath);
        return doc;
    }

    @Test
    void viewer_anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/documents/42/viewer"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void viewer_unauthorizedForThisDocument_isForbidden() throws Exception {
        // viewerFilePath points at a path that is never actually resolved to
        // bytes here — reaching 403 (not 404/200) proves the authorization
        // check short-circuits before any file access is attempted.
        when(userRepository.findByEmail("employee@company.com")).thenReturn(Optional.of(employee()));
        when(documentService.getDocument(42L)).thenReturn(privateDocOwnedByOther("does-not-matter.pdf"));
        when(documentAccessService.canAccessDocument(any(), any())).thenReturn(false);

        mockMvc.perform(get("/api/documents/42/viewer"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void viewer_documentNotFound_isNotFound() throws Exception {
        when(userRepository.findByEmail("employee@company.com")).thenReturn(Optional.of(employee()));
        when(documentService.getDocument(42L)).thenThrow(new RuntimeException("Tài liệu không tồn tại"));

        mockMvc.perform(get("/api/documents/42/viewer"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void viewer_notYetConverted_isNotFound() throws Exception {
        when(userRepository.findByEmail("employee@company.com")).thenReturn(Optional.of(employee()));
        when(documentService.getDocument(42L)).thenReturn(privateDocOwnedByOther(null));
        when(documentAccessService.canAccessDocument(any(), any())).thenReturn(true);

        mockMvc.perform(get("/api/documents/42/viewer"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void viewer_authorizedAndReady_streamsPdf() throws Exception {
        Path pdfFile = tempDir.resolve("viewer.pdf");
        Files.write(pdfFile, "%PDF-1.4 fake content".getBytes());

        when(userRepository.findByEmail("employee@company.com")).thenReturn(Optional.of(employee()));
        when(documentService.getDocument(42L)).thenReturn(privateDocOwnedByOther(pdfFile.toString()));
        when(documentAccessService.canAccessDocument(any(), any())).thenReturn(true);

        mockMvc.perform(get("/api/documents/42/viewer"))
                .andExpect(status().isOk())
                // App-wide CharacterEncodingFilter (force-response=true) appends
                // ";charset=UTF-8" to every response, so compare compatibility
                // rather than an exact literal match.
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF));
    }

    // GET /api/documents/{id}/viewer-status must follow the exact same
    // authenticate -> authorize -> exists checklist as /viewer above, since
    // it's just a lightweight status probe backing the frontend's bounded
    // polling and must not become a document-metadata IDOR side-channel.

    @Test
    void viewerStatus_anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/documents/42/viewer-status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void viewerStatus_unauthorizedForThisDocument_isForbidden() throws Exception {
        when(userRepository.findByEmail("employee@company.com")).thenReturn(Optional.of(employee()));
        when(documentService.getDocument(42L)).thenReturn(privateDocOwnedByOther("does-not-matter.pdf"));
        when(documentAccessService.canAccessDocument(any(), any())).thenReturn(false);

        mockMvc.perform(get("/api/documents/42/viewer-status"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void viewerStatus_documentNotFound_isNotFound() throws Exception {
        when(userRepository.findByEmail("employee@company.com")).thenReturn(Optional.of(employee()));
        when(documentService.getDocument(42L)).thenThrow(new RuntimeException("Tài liệu không tồn tại"));

        mockMvc.perform(get("/api/documents/42/viewer-status"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void viewerStatus_authorized_returnsCurrentStatus() throws Exception {
        Document doc = privateDocOwnedByOther(null);
        doc.setViewerStatus(ViewerStatus.PROCESSING);

        when(userRepository.findByEmail("employee@company.com")).thenReturn(Optional.of(employee()));
        when(documentService.getDocument(42L)).thenReturn(doc);
        when(documentAccessService.canAccessDocument(any(), any())).thenReturn(true);

        mockMvc.perform(get("/api/documents/42/viewer-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }
}
