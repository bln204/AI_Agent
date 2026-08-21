package com.aiagent.service;

import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.DocumentClassification;
import com.aiagent.model.Project;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.rag.DocumentIngestionService;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.ProjectRepository;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Covers the PROJECT-scope upload authorization gate in
 * DocumentService#uploadDocument (business decision, see
 * DocumentAccessService#canUploadToProject): only DIRECTOR (any project) or
 * the LEADER of the specific project may upload PROJECT-scope documents.
 * A MANAGER who is merely a member (not the leader) of the project must be
 * DENIED, even though MANAGER still passes the generic canUpload() check
 * used for DEPARTMENT/PUBLIC uploads -- this is exactly the bug reported:
 * a department-head member (not leader) could previously upload into a
 * project via the blanket canUpload() bypass.
 */
class DocumentServiceProjectUploadAuthorizationTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentIngestionService documentIngestionService;
    @Mock
    private DepartmentRepository departmentRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private DocumentAccessService documentAccessService;
    @Mock
    private DecisionNumberService decisionNumberService;
    @Mock
    private DocumentDuplicateDetectionService documentDuplicateDetectionService;
    @Mock
    private DocumentViewerConversionService documentViewerConversionService;
    @Mock
    private NotificationService notificationService;

    @TempDir
    Path uploadDir;

    private DocumentService documentService;
    private Project project;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        documentService = new DocumentService(documentRepository, documentIngestionService,
                departmentRepository, projectRepository, documentAccessService, documentViewerConversionService,
                notificationService, decisionNumberService, documentDuplicateDetectionService);
        ReflectionTestUtils.setField(documentService, "uploadDir", uploadDir.toString());
        ReflectionTestUtils.setField(documentService, "maxUploadSizeMb", 50L);

        project = new Project();
        project.setId(10L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(1L);
            return d;
        });
    }

    private User user(String roleCode) {
        Role role = new Role();
        role.setCode(roleCode);
        User u = new User();
        u.setId(1L);
        u.setUsername("user");
        u.setRole(role);
        return u;
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "report.pdf", "application/pdf",
                ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF").getBytes(StandardCharsets.US_ASCII));
    }

    private Document uploadToProject(User uploader) throws IOException {
        return documentService.uploadDocument("Title", null, null, List.of(10L), AccessLevel.PROJECT,
                null, DocumentClassification.OTHER, "Project X", "desc", true, pdf(), uploader, true);
    }

    @Test
    void manager_memberButNotLeader_isDenied() {
        User manager = user(RoleConstants.ROLE_MANAGER);
        when(documentAccessService.canUpload(manager)).thenReturn(true); // passes generic DEPARTMENT/PUBLIC gate
        when(documentAccessService.canUploadToProject(manager, project)).thenReturn(false); // not this project's leader

        assertThrows(SecurityException.class, () -> uploadToProject(manager));
    }

    @Test
    void manager_isLeaderOfThisProject_isAllowed() throws Exception {
        User manager = user(RoleConstants.ROLE_MANAGER);
        when(documentAccessService.canUploadToProject(manager, project)).thenReturn(true);

        Document doc = uploadToProject(manager);
        assertNotNull(doc.getFilePath());
    }

    @Test
    void employee_isLeaderOfThisProject_isAllowed() throws Exception {
        User employee = user(RoleConstants.ROLE_EMPLOYEE);
        when(documentAccessService.canUpload(employee)).thenReturn(false);
        when(documentAccessService.canUploadToProject(employee, project)).thenReturn(true);

        Document doc = uploadToProject(employee);
        assertNotNull(doc.getFilePath());
    }

    @Test
    void director_notMemberOfProject_isStillAllowed() throws Exception {
        User director = user(RoleConstants.ROLE_DIRECTOR);
        when(documentAccessService.canUploadToProject(director, project)).thenReturn(true);

        Document doc = uploadToProject(director);
        assertNotNull(doc.getFilePath());
    }

    @Test
    void employee_memberButNotLeader_isDenied() {
        User employee = user(RoleConstants.ROLE_EMPLOYEE);
        when(documentAccessService.canUpload(employee)).thenReturn(false);
        when(documentAccessService.canUploadToProject(employee, project)).thenReturn(false);

        assertThrows(SecurityException.class, () -> uploadToProject(employee));
    }

    @Test
    void nonProjectScope_stillUsesGenericCanUpload_unaffectedByProjectGate() throws Exception {
        User manager = user(RoleConstants.ROLE_MANAGER);
        when(documentAccessService.canUpload(manager)).thenReturn(true);

        Document doc = documentService.uploadDocument("Title", null, null, null, AccessLevel.PUBLIC,
                null, DocumentClassification.OTHER, null, "desc", true, pdf(), manager, true);

        assertNotNull(doc.getFilePath());
    }
}
