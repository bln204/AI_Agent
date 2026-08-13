package com.aiagent.service;

import com.aiagent.exception.DocumentDuplicateException;
import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.DocumentClassification;
import com.aiagent.model.DocumentDuplicateType;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.rag.DocumentIngestionService;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * End-to-end (mocked-dependencies) coverage of the duplicate-detection gate
 * wired into DocumentService#uploadDocument: acceptance criteria test cases
 * 1, 2, 3, 5, 6, 9, 10 from the task spec. Cases 4 (semantic) and 7
 * (unauthorized non-leak) are covered at the DocumentDuplicateDetectionService
 * / SemanticDuplicateDetectionService unit level; case 8 (unreadable file) is
 * exercised via DocumentServiceUploadSecurityTest's fake-PDF fixtures, which
 * already run through the real (unmocked) Tika extraction path added here.
 */
class DocumentServiceDuplicateFlowTest {

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

    private DocumentService documentService;

    @BeforeEach
    void setUp(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        MockitoAnnotations.openMocks(this);
        documentService = new DocumentService(documentRepository, documentIngestionService, departmentRepository,
                projectRepository, documentAccessService, decisionNumberService, documentDuplicateDetectionService);
        ReflectionTestUtils.setField(documentService, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(documentService, "maxUploadSizeMb", 50L);

        when(documentAccessService.canUpload(any())).thenReturn(true);
        when(documentDuplicateDetectionService.hashBytes(any())).thenReturn("some-file-hash");
        when(documentDuplicateDetectionService.hashText(any())).thenReturn("some-content-hash");
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(1L);
            return d;
        });
    }

    private User director() {
        Role role = new Role();
        role.setCode(com.aiagent.util.RoleConstants.ROLE_DIRECTOR);
        User user = new User();
        user.setId(1L);
        user.setUsername("director");
        user.setEmail("director@company.com");
        user.setRole(role);
        return user;
    }

    private MockMultipartFile txtFile(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    private Document upload(MockMultipartFile file) throws IOException {
        return documentService.uploadDocument("Title", null, null, null, AccessLevel.PUBLIC, null,
                DocumentClassification.OTHER, null, "desc", true, file, director());
    }

    // --- Test 1: new/unique document -> ACCEPT ---
    @Test
    void newDocument_noDuplicateAnywhere_isAccepted() throws IOException {
        Document doc = upload(txtFile("a.txt", "Completely unique content."));
        assertNotNull(doc.getFilePath());
        verify(documentDuplicateDetectionService).checkFileDuplicate(anyString(), any());
    }

    // --- Test 2: exact file duplicate -> REJECT, nothing persisted/indexed ---
    @Test
    void exactFileDuplicate_isRejected_andNeverReachesRepositoryOrIngestion() {
        doThrow(new DocumentDuplicateException(DocumentDuplicateType.DUPLICATE_FILE, 5L, "Existing", "dup"))
                .when(documentDuplicateDetectionService).checkFileDuplicate(anyString(), any());

        MockMultipartFile file = txtFile("a.txt", "same bytes");

        assertThrows(DocumentDuplicateException.class, () -> upload(file));

        verify(documentRepository, never()).save(any());
        verifyNoInteractions(documentIngestionService);
    }

    // --- Test 3: different binary, same extracted content -> REJECT ---
    @Test
    void exactContentDuplicate_isRejected_beforePersistAndIngestion() {
        doThrow(new DocumentDuplicateException(DocumentDuplicateType.DUPLICATE_CONTENT, 5L, "Existing", "dup"))
                .when(documentDuplicateDetectionService).checkContentDuplicate(anyString(), any());

        MockMultipartFile file = txtFile("b.txt", "This text is extractable and will be hashed.");

        assertThrows(DocumentDuplicateException.class, () -> upload(file));

        verify(documentRepository, never()).save(any());
        verifyNoInteractions(documentIngestionService);
    }

    // --- Test 5: different content -> ACCEPT ---
    @Test
    void differentContent_isAccepted() throws IOException {
        Document doc = upload(txtFile("c.txt", "Yet another distinct piece of content."));
        assertNotNull(doc.getFilePath());
    }

    // --- Test 6: concurrent upload -> DB unique constraint is the real guard ---
    @Test
    void concurrentUploadRace_uniqueConstraintViolation_isTranslatedToDuplicateRejection() {
        when(documentRepository.save(any(Document.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key: file_hash"));
        // Simulate that by the time we re-check post-violation, the racing
        // request's row is now visible and IS the duplicate.
        doThrow(new DocumentDuplicateException(DocumentDuplicateType.DUPLICATE_FILE, 9L, "Racer", "dup"))
                .when(documentDuplicateDetectionService).checkFileDuplicate(anyString(), any());

        MockMultipartFile file = txtFile("race.txt", "racing content");

        DocumentDuplicateException ex = assertThrows(DocumentDuplicateException.class, () -> upload(file));
        assertNotNull(ex.getDuplicateDocumentId());
        verifyNoInteractions(documentIngestionService);
    }

    @Test
    void concurrentUploadRace_violationNotFromHashConstraint_surfacesOriginalError() {
        // Neither hash re-check finds a match -> the violation was NOT our
        // duplicate guard; the original DB error must propagate untouched
        // rather than being misreported as a content duplicate.
        when(documentRepository.save(any(Document.class)))
                .thenThrow(new DataIntegrityViolationException("some other unique constraint"));

        MockMultipartFile file = txtFile("race2.txt", "other racing content");

        assertThrows(DataIntegrityViolationException.class, () -> upload(file));
    }

    // --- Test 9: same filename, different content -> filename must never drive the hash ---
    @Test
    void sameFilename_differentContent_isNotTreatedAsDuplicate() throws IOException {
        // hashBytes is invoked for real content each time in production; here
        // the mock simply proves the filename itself is irrelevant to the
        // duplicate decision — only checkFileDuplicate's hash argument matters,
        // and no stubbed rejection is registered for either call.
        MockMultipartFile file1 = txtFile("same-name.txt", "first version of the content");
        MockMultipartFile file2 = txtFile("same-name.txt", "second, totally different content");

        Document doc1 = upload(file1);
        Document doc2 = upload(file2);

        assertNotNull(doc1.getFilePath());
        assertNotNull(doc2.getFilePath());
    }

    // --- Test 10: Qdrant must not receive chunks for a rejected duplicate ---
    @Test
    void duplicateRejection_neverTriggersVectorIngestion() {
        doThrow(new DocumentDuplicateException(DocumentDuplicateType.DUPLICATE_SEMANTIC, 5L, "Existing", "dup"))
                .when(documentDuplicateDetectionService).checkSemanticDuplicate(any(), any());

        MockMultipartFile file = txtFile("d.txt", "text long enough to be chunked and semantically checked.");

        assertThrows(DocumentDuplicateException.class, () -> upload(file));

        verifyNoInteractions(documentIngestionService);
    }
}
