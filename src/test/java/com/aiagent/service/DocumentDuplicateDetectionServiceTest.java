package com.aiagent.service;

import com.aiagent.exception.DocumentDuplicateException;
import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.DocumentDuplicateType;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.rag.SemanticDuplicateDetectionService;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the orchestrator's core contract: exact-match rejection at file and
 * content level, delegation to semantic detection, and — most importantly —
 * that a matched document's identity is only disclosed when the requesting
 * uploader can actually access it (WORKING_RULES §17: no IDOR-style leak of
 * documents outside the requester's permission scope).
 */
class DocumentDuplicateDetectionServiceTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentAccessService documentAccessService;
    @Mock
    private SemanticDuplicateDetectionService semanticDuplicateDetectionService;

    private DocumentDuplicateDetectionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new DocumentDuplicateDetectionService(documentRepository, documentAccessService, semanticDuplicateDetectionService);
        ReflectionTestUtils.setField(service, "duplicateDetectionEnabled", true);
    }

    private User requester() {
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_MANAGER);
        User user = new User();
        user.setId(1L);
        user.setEmail("manager@company.com");
        user.setRole(role);
        return user;
    }

    private Document existingDocument() {
        Document doc = new Document();
        doc.setId(99L);
        doc.setTitle("Existing Report");
        doc.setAccessLevel(AccessLevel.PRIVATE);
        return doc;
    }

    // --- Hashing ---

    @Test
    void hashBytes_isDeterministic_sameBytesProduceSameHash() throws Exception {
        String h1 = service.hashBytes(new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8)));
        String h2 = service.hashBytes(new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8)));
        assertEquals(h1, h2);
        assertEquals(64, h1.length(), "SHA-256 hex digest must be 64 chars");
    }

    @Test
    void hashBytes_differentContent_producesDifferentHash() throws Exception {
        String h1 = service.hashBytes(new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8)));
        String h2 = service.hashBytes(new ByteArrayInputStream("hello WORLD".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(h1, h2);
    }

    @Test
    void hashText_isDeterministic() {
        assertEquals(service.hashText("normalized text"), service.hashText("normalized text"));
    }

    // --- Level 1: exact file duplicate ---

    @Test
    void checkFileDuplicate_noMatch_doesNotThrow() {
        when(documentRepository.findByFileHashAndIsDeletedFalse("abc")).thenReturn(Optional.empty());
        service.checkFileDuplicate("abc", requester());
        // no exception = pass
    }

    @Test
    void checkFileDuplicate_match_requesterHasAccess_disclosesIdentity() {
        Document existing = existingDocument();
        when(documentRepository.findByFileHashAndIsDeletedFalse("abc")).thenReturn(Optional.of(existing));
        when(documentAccessService.canAccessDocument(any(), any())).thenReturn(true);

        DocumentDuplicateException ex = assertThrows(DocumentDuplicateException.class,
                () -> service.checkFileDuplicate("abc", requester()));

        assertEquals(DocumentDuplicateType.DUPLICATE_FILE, ex.getDuplicateType());
        assertEquals(99L, ex.getDuplicateDocumentId());
        assertEquals("Existing Report", ex.getDuplicateDocumentName());
    }

    @Test
    void checkFileDuplicate_match_requesterHasNoAccess_stillRejectsButDoesNotDiscloseIdentity() {
        Document existing = existingDocument();
        when(documentRepository.findByFileHashAndIsDeletedFalse("abc")).thenReturn(Optional.of(existing));
        when(documentAccessService.canAccessDocument(any(), any())).thenReturn(false);

        DocumentDuplicateException ex = assertThrows(DocumentDuplicateException.class,
                () -> service.checkFileDuplicate("abc", requester()));

        assertEquals(DocumentDuplicateType.DUPLICATE_FILE, ex.getDuplicateType());
        assertNull(ex.getDuplicateDocumentId(), "must not leak id of a document the requester cannot access");
        assertNull(ex.getDuplicateDocumentName(), "must not leak name of a document the requester cannot access");
    }

    @Test
    void checkFileDuplicate_nullHash_isNoOp() {
        service.checkFileDuplicate(null, requester());
        verifyNoInteractions(documentRepository);
    }

    // --- Level 2: exact content duplicate ---

    @Test
    void checkContentDuplicate_match_throwsWithContentType() {
        Document existing = existingDocument();
        when(documentRepository.findByContentHashAndIsDeletedFalse("hash123")).thenReturn(Optional.of(existing));
        when(documentAccessService.canAccessDocument(any(), any())).thenReturn(true);

        DocumentDuplicateException ex = assertThrows(DocumentDuplicateException.class,
                () -> service.checkContentDuplicate("hash123", requester()));

        assertEquals(DocumentDuplicateType.DUPLICATE_CONTENT, ex.getDuplicateType());
    }

    @Test
    void checkContentDuplicate_noMatch_doesNotThrow() {
        when(documentRepository.findByContentHashAndIsDeletedFalse("hash123")).thenReturn(Optional.empty());
        service.checkContentDuplicate("hash123", requester());
    }

    // --- Level 3: semantic duplicate delegation ---

    @Test
    void checkSemanticDuplicate_delegateFindsMatch_throwsWithSemanticType() {
        Document existing = existingDocument();
        List<org.springframework.ai.document.Document> chunks = List.of(
                new org.springframework.ai.document.Document("some chunk text"));
        when(semanticDuplicateDetectionService.findDuplicate(chunks, requester()))
                .thenReturn(Optional.of(new SemanticDuplicateDetectionService.SemanticMatch(existing, 0.95, 0.8)));
        when(documentAccessService.canAccessDocument(any(), any())).thenReturn(true);

        DocumentDuplicateException ex = assertThrows(DocumentDuplicateException.class,
                () -> service.checkSemanticDuplicate(chunks, requester()));

        assertEquals(DocumentDuplicateType.DUPLICATE_SEMANTIC, ex.getDuplicateType());
        assertEquals(99L, ex.getDuplicateDocumentId());
    }

    @Test
    void checkSemanticDuplicate_delegateFindsNoMatch_doesNotThrow() {
        List<org.springframework.ai.document.Document> chunks = List.of(
                new org.springframework.ai.document.Document("some chunk text"));
        when(semanticDuplicateDetectionService.findDuplicate(chunks, requester())).thenReturn(Optional.empty());

        service.checkSemanticDuplicate(chunks, requester());
    }

    @Test
    void checkSemanticDuplicate_emptyChunks_isNoOp() {
        service.checkSemanticDuplicate(List.of(), requester());
        verifyNoInteractions(semanticDuplicateDetectionService);
    }

    // --- Master switch (rollback safety) ---

    @Test
    void whenDisabled_allChecksAreNoOp_evenWithAMatchPresent() {
        ReflectionTestUtils.setField(service, "duplicateDetectionEnabled", false);
        when(documentRepository.findByFileHashAndIsDeletedFalse("abc")).thenReturn(Optional.of(existingDocument()));

        service.checkFileDuplicate("abc", requester());
        // no exception, and repository must not even be queried
        verifyNoInteractions(documentRepository);
    }
}
