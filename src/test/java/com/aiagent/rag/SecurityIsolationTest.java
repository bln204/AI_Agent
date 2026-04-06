package com.aiagent.rag;

import com.aiagent.model.AccessLevel;
import com.aiagent.model.Department;
import com.aiagent.model.Project;
import com.aiagent.model.User;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.service.AccessPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SecurityIsolationTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private AccessPolicyService accessPolicyService;

    @Mock
    private QueryIntentClassifier queryIntentClassifier;

    @Mock
    private org.springframework.cache.CacheManager cacheManager;

    private RagRetrievalService ragRetrievalService;

    private User userA;
    private User userB;
    private com.aiagent.model.Document docProject1;
    private com.aiagent.model.Document docProject2;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Manual instantiation
        ragRetrievalService = new RagRetrievalService(vectorStore, documentRepository, accessPolicyService, queryIntentClassifier, cacheManager);
        
        // Fix @Value fields since manual instantiation doesn't populate them
        ReflectionTestUtils.setField(ragRetrievalService, "semanticTopK", 25);
        ReflectionTestUtils.setField(ragRetrievalService, "anchoredTopK", 10);

        // Mock default Intent Classification Result
        when(queryIntentClassifier.classify(anyString(), any()))
                .thenReturn(new QueryIntentClassifier.ClassificationResult(
                        QueryIntentClassifier.Intent.MIXED, 
                        Collections.emptySet()));

        userA = new User();
        userA.setId(1L);
        userA.setEmail("userA@company.com");

        Department dept1 = new Department();
        dept1.setId(10L);
        userA.setDepartment(dept1);

        userB = new User();
        userB.setId(2L);
        userB.setEmail("userB@other.com");

        Project p1 = new Project();
        p1.setId(100L);
        p1.setCode("P1");

        Project p2 = new Project();
        p2.setId(200L);
        p2.setCode("P2");

        docProject1 = new com.aiagent.model.Document();
        docProject1.setId(1001L);
        docProject1.setTitle("Doc P1");
        docProject1.setAccessLevel(AccessLevel.PROJECT);
        docProject1.setProjects(Set.of(p1));

        docProject2 = new com.aiagent.model.Document();
        docProject2.setId(1002L);
        docProject2.setTitle("Doc P2");
        docProject2.setAccessLevel(AccessLevel.PROJECT);
        docProject2.setProjects(Set.of(p2));
    }

    @Test
    @DisplayName("Layer 2 Leak Prevention: Should block documents not in user projects")
    void testProjectIsolationLeakPrevention() {
        Document leakedHit = new Document("leaked context", Map.of(
                "document_id", "1002",
                "document_name", "Doc P2",
                "score", 0.9));

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(leakedHit));
        when(documentRepository.findById(1002L)).thenReturn(Optional.of(docProject2));
        when(accessPolicyService.canAccessDocument(eq(userA), eq(docProject2))).thenReturn(false);

        List<Document> results = ragRetrievalService.retrieveContext("test query", userA, Collections.emptySet());

        assertTrue(results.isEmpty(), "Results should be empty because Layer 2 blocked the leaked document");
        verify(accessPolicyService).canAccessDocument(userA, docProject2);
    }

    @Test
    @DisplayName("Quality Guard: Should discard chunks with very low similarity scores")
    void testLowQualityDiscard() {
        Document lowScoreHit = new Document("irrelevant content", Map.of(
                "document_id", "1001",
                "score", 0.2));

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(lowScoreHit));
        when(documentRepository.findById(1001L)).thenReturn(Optional.of(docProject1));
        when(accessPolicyService.canAccessDocument(any(), any())).thenReturn(true);

        List<Document> results = ragRetrievalService.retrieveContext("test query", userA, Collections.emptySet());

        assertTrue(results.isEmpty(), "Results should be empty because score 0.2 < 0.35 threshold");
    }

    @Test
    @DisplayName("Multi-Project Support: Should allow hits for users in multiple projects")
    void testMultiProjectAllowed() {
        Document hit1 = new Document("p1 info", Map.of("document_id", "1001", "score", 0.8));
        Document hit2 = new Document("p2 info", Map.of("document_id", "1002", "score", 0.85));

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(hit1, hit2));
        
        // Mock findAllById as it's used in the service now
        when(documentRepository.findAllById(anyList())).thenAnswer(invocation -> {
            java.util.List<Long> ids = invocation.getArgument(0);
            List<com.aiagent.model.Document> docs = new ArrayList<>();
            if (ids.contains(1001L)) docs.add(docProject1);
            if (ids.contains(1002L)) docs.add(docProject2);
            return docs;
        });

        when(documentRepository.findById(1001L)).thenReturn(Optional.of(docProject1));
        when(documentRepository.findById(1002L)).thenReturn(Optional.of(docProject2));
        when(accessPolicyService.canAccessDocument(eq(userA), any())).thenReturn(true);

        List<Document> results = ragRetrievalService.retrieveContext("test query", userA, Collections.emptySet());

        assertEquals(2, results.size());
    }
}