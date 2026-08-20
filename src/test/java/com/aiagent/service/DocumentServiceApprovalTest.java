package com.aiagent.service;

import com.aiagent.model.AccessLevel;
import com.aiagent.model.Department;
import com.aiagent.model.Document;
import com.aiagent.model.DocumentClassification;
import com.aiagent.model.DocumentStatus;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.rag.DocumentIngestionService;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.ProjectRepository;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the approval-workflow business logic (Phase 2/3 of the approved
 * plan): MANAGER uploads are gated at PENDING_APPROVAL with no ingestion,
 * DIRECTOR-only approve()/reject(), and the double-approval guard (decision
 * #9). The "replace file while pending" flow was removed (decision:
 * PENDING_APPROVAL documents can no longer be edited -- Manager must upload
 * a fresh document instead; see DocumentDuplicateDetectionServiceTest for the
 * matching duplicate-check relaxation that makes that re-upload possible).
 */
class DocumentServiceApprovalTest {

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
    private DocumentViewerConversionService documentViewerConversionService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private DecisionNumberService decisionNumberService;
    @Mock
    private DocumentDuplicateDetectionService documentDuplicateDetectionService;

    private DocumentService documentService;
    private Path tempDir;

    @BeforeEach
    void setUp(@org.junit.jupiter.api.io.TempDir Path tempDir) {
        MockitoAnnotations.openMocks(this);
        this.tempDir = tempDir;
        documentService = new DocumentService(documentRepository, documentIngestionService, departmentRepository,
                projectRepository, documentAccessService, documentViewerConversionService, notificationService,
                decisionNumberService, documentDuplicateDetectionService);
        ReflectionTestUtils.setField(documentService, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(documentService, "maxUploadSizeMb", 50L);
    }

    private User manager() {
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_MANAGER);
        User u = new User();
        u.setId(1L);
        u.setUsername("manager1");
        u.setEmail("manager@company.com");
        u.setRole(role);
        Department dept = new Department();
        dept.setId(10L);
        dept.setCode("HR");
        dept.setName("Nhan su");
        u.setDepartment(dept);
        return u;
    }

    private User director() {
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_DIRECTOR);
        User u = new User();
        u.setId(2L);
        u.setUsername("director1");
        u.setEmail("director@company.com");
        u.setRole(role);
        return u;
    }

    private User employee() {
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_EMPLOYEE);
        User u = new User();
        u.setId(3L);
        u.setEmail("employee@company.com");
        u.setRole(role);
        return u;
    }

    // --- Upload-time status assignment + ingestion gate ---

    @Test
    void uploadDocument_managerUpload_isPendingApproval_andNeverIngests() throws IOException {
        when(documentAccessService.canUpload(any())).thenReturn(true);
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            if (d.getId() == null) d.setId(100L);
            return d;
        });

        Document saved = documentService.uploadDocument("Title", "content", null, null, AccessLevel.PUBLIC,
                null, DocumentClassification.OTHER, null, "desc", true, null, manager());

        assertEquals(DocumentStatus.PENDING_APPROVAL, saved.getStatus());
        verify(documentIngestionService, never()).ingestDocument(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyList(), anyList(), any(), any(), any(), any(), any());
        verify(documentIngestionService, never()).ingestPreExtracted(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyList(), anyList(), any(), any(), any(), any(), any());
        verify(notificationService).notifyDirectorsOfPendingDocument(saved);
    }

    @Test
    void uploadDocument_directorUpload_isApproved_immediately() throws IOException {
        when(documentAccessService.canUpload(any())).thenReturn(true);
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            if (d.getId() == null) d.setId(101L);
            return d;
        });

        Document saved = documentService.uploadDocument("Title", "content", null, null, AccessLevel.PUBLIC,
                null, DocumentClassification.OTHER, null, "desc", true, null, director());

        assertEquals(DocumentStatus.APPROVED, saved.getStatus());
        verify(notificationService, never()).notifyDirectorsOfPendingDocument(any());
    }

    // --- approveDocument ---

    @Test
    void approveDocument_nonDirector_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> documentService.approveDocument(1L, employee()));
        assertThrows(SecurityException.class, () -> documentService.approveDocument(1L, manager()));
        verify(documentRepository, never()).approveIfPending(any(), any(), any());
    }

    @Test
    void approveDocument_pendingDocument_transitionsToApproved_andTriggersIngestion() throws IOException {
        // A real file is required: approveDocument() now re-hashes the file from
        // disk (byte-for-byte identical to the upload-time hash) so it can persist
        // file_hash/content_hash once the document becomes APPROVED -- see
        // DocumentService#assignApprovedHashes. Those columns are left NULL while
        // PENDING_APPROVAL/REJECTED so the UNIQUE DB constraint on them only ever
        // guards APPROVED documents (matching DocumentDuplicateDetectionService's
        // APPROVED-only check) and no longer blocks a Manager resubmitting the same
        // file/content after a rejection.
        Path filePath = tempDir.resolve("some-file.pdf");
        java.nio.file.Files.write(filePath, "pdf-bytes".getBytes());

        Document approvedDoc = new Document();
        approvedDoc.setId(5L);
        approvedDoc.setStatus(DocumentStatus.APPROVED);
        approvedDoc.setAccessLevel(AccessLevel.PUBLIC);
        approvedDoc.setClassification(DocumentClassification.OTHER);
        approvedDoc.setUploadedBy(manager());
        approvedDoc.setFilePath(filePath.toString());
        approvedDoc.setFileType("PDF");

        when(documentRepository.approveIfPending(eq(5L), eq(director()), any())).thenReturn(1);
        when(documentRepository.findById(5L)).thenReturn(Optional.of(approvedDoc));
        when(documentDuplicateDetectionService.hashBytes(any())).thenReturn("computed-file-hash");

        Document result = documentService.approveDocument(5L, director());

        assertEquals(DocumentStatus.APPROVED, result.getStatus());
        assertEquals("computed-file-hash", result.getFileHash());
        verify(documentDuplicateDetectionService).checkFileDuplicate("computed-file-hash", director());
        verify(documentIngestionService).ingestDocument(eq(filePath.toString()), eq(5L), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyList(), anyList(), any(), any(), any(), any(), any());
        verify(notificationService).notifyUploaderOfDecision(result, true);
    }

    @Test
    void approveDocument_alreadyProcessed_throwsIllegalStateException() {
        Document rejectedAlready = new Document();
        rejectedAlready.setId(6L);
        rejectedAlready.setStatus(DocumentStatus.REJECTED);

        // WHERE status = PENDING_APPROVAL matches 0 rows -- someone else already
        // rejected it before this request's UPDATE ran (decision #9).
        when(documentRepository.approveIfPending(eq(6L), any(), any())).thenReturn(0);
        when(documentRepository.findById(6L)).thenReturn(Optional.of(rejectedAlready));

        assertThrows(IllegalStateException.class, () -> documentService.approveDocument(6L, director()));
        verify(documentIngestionService, never()).ingestDocument(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyList(), anyList(), any(), any(), any(), any(), any());
    }

    // --- rejectDocument ---

    @Test
    void rejectDocument_nonDirector_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> documentService.rejectDocument(1L, manager()));
        verify(documentRepository, never()).rejectIfPending(any(), any(), any());
    }

    @Test
    void rejectDocument_pendingDocument_transitionsToRejected_andPurgesVectorStoreDefensively() {
        Document rejectedDoc = new Document();
        rejectedDoc.setId(7L);
        rejectedDoc.setStatus(DocumentStatus.REJECTED);
        rejectedDoc.setUploadedBy(manager());

        when(documentRepository.rejectIfPending(eq(7L), eq(director()), any())).thenReturn(1);
        when(documentRepository.findById(7L)).thenReturn(Optional.of(rejectedDoc));

        Document result = documentService.rejectDocument(7L, director());

        assertEquals(DocumentStatus.REJECTED, result.getStatus());
        verify(documentIngestionService).deleteFromVectorStore(7L);
        verify(notificationService).notifyUploaderOfDecision(result, false);
    }

    @Test
    void rejectDocument_alreadyProcessed_throwsIllegalStateException() {
        Document approvedAlready = new Document();
        approvedAlready.setId(8L);
        approvedAlready.setStatus(DocumentStatus.APPROVED);

        when(documentRepository.rejectIfPending(eq(8L), any(), any())).thenReturn(0);
        when(documentRepository.findById(8L)).thenReturn(Optional.of(approvedAlready));

        assertThrows(IllegalStateException.class, () -> documentService.rejectDocument(8L, director()));
    }
}
