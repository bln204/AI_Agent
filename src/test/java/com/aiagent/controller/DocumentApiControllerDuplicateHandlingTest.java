package com.aiagent.controller;

import com.aiagent.config.AuthRateLimitFilter;
import com.aiagent.config.SecurityConfig;
import com.aiagent.exception.DocumentDuplicateException;
import com.aiagent.model.AccessLevel;
import com.aiagent.model.DocumentClassification;
import com.aiagent.model.DocumentDuplicateType;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the /api/documents/upload duplicate-rejection HTTP contract:
 * DocumentDuplicateException must become HTTP 409 with the structured
 * {error, duplicateType, duplicateDocumentId, duplicateDocumentName, message}
 * body (reusing the "error" key convention AiGlobalExceptionHandler already
 * uses elsewhere), and must never leak identity of a document the requester
 * cannot access.
 */
@WebMvcTest(DocumentApiController.class)
@Import({SecurityConfig.class, AuthRateLimitFilter.class, RateLimiterService.class})
class DocumentApiControllerDuplicateHandlingTest {

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

    private User director() {
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_DIRECTOR);
        User user = new User();
        user.setId(1L);
        user.setEmail("director@company.com");
        user.setRole(role);
        return user;
    }

    @Test
    @WithMockUser(username = "director@company.com")
    void uploadDuplicateFile_returnsConflictWithDisclosedIdentity() throws Exception {
        when(userRepository.findByEmail("director@company.com")).thenReturn(Optional.of(director()));
        when(documentService.uploadDocument(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new DocumentDuplicateException(DocumentDuplicateType.DUPLICATE_FILE, 7L, "Báo cáo cuối kỳ",
                        "Tài liệu này đã tồn tại trên hệ thống và không thể tải lên."));

        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .param("title", "Báo cáo")
                        .param("accessLevel", AccessLevel.PUBLIC.name())
                        .param("classification", DocumentClassification.OTHER.name())
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DOCUMENT_DUPLICATE"))
                .andExpect(jsonPath("$.duplicateType").value("DUPLICATE_FILE"))
                .andExpect(jsonPath("$.duplicateDocumentId").value(7))
                .andExpect(jsonPath("$.duplicateDocumentName").value("Báo cáo cuối kỳ"));
    }

    @Test
    @WithMockUser(username = "director@company.com")
    void uploadDuplicateContent_unauthorizedToViewExisting_doesNotLeakIdentity() throws Exception {
        when(userRepository.findByEmail("director@company.com")).thenReturn(Optional.of(director()));
        when(documentService.uploadDocument(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new DocumentDuplicateException(DocumentDuplicateType.DUPLICATE_CONTENT, null, null,
                        "Tài liệu này đã tồn tại trên hệ thống và không thể tải lên."));

        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .param("title", "Báo cáo")
                        .param("accessLevel", AccessLevel.PUBLIC.name())
                        .param("classification", DocumentClassification.OTHER.name())
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DOCUMENT_DUPLICATE"))
                .andExpect(jsonPath("$.duplicateDocumentId").value(nullValue()))
                .andExpect(jsonPath("$.duplicateDocumentName").value(nullValue()));
    }
}
