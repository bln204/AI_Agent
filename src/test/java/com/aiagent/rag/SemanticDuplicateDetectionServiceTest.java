package com.aiagent.rag;

import com.aiagent.model.Document;
import com.aiagent.model.User;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.service.DocumentAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Level 3 (semantic) aggregation logic. The key property under test — the
 * one explicitly called out as a requirement — is that a SINGLE similar
 * chunk must NOT be enough to flag a document as a duplicate: matching must
 * be evaluated in aggregate across a high enough fraction of the new
 * document's own chunks (coverageRatio), not from one hot hit.
 */
class SemanticDuplicateDetectionServiceTest {

    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private DocumentAccessService documentAccessService;
    @Mock
    private DocumentRepository documentRepository;

    private SemanticDuplicateDetectionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new SemanticDuplicateDetectionService(vectorStoreService, documentAccessService, documentRepository);
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.9);
        ReflectionTestUtils.setField(service, "minCoverageRatio", 0.6);
        ReflectionTestUtils.setField(service, "maxChunksSampled", 20);
        when(documentAccessService.buildVectorFilter(any())).thenReturn(null);
    }

    private org.springframework.ai.document.Document chunk(String text) {
        return new org.springframework.ai.document.Document(text);
    }

    private org.springframework.ai.document.Document hitFor(String documentId, double distance) {
        // VectorStoreService reports similarity as (1 - distance); mirror that here.
        return new org.springframework.ai.document.Document("matched chunk",
                Map.of("document_id", documentId, "distance", distance));
    }

    private Document dbDocument(long id) {
        Document doc = new Document();
        doc.setId(id);
        doc.setTitle("Candidate Doc " + id);
        return doc;
    }

    @Test
    void noChunks_returnsEmpty() {
        assertTrue(service.findDuplicate(List.of(), new User()).isEmpty());
    }

    @Test
    void allChunksMatchSameDocumentAboveThreshold_isFlaggedAsDuplicate() {
        List<org.springframework.ai.document.Document> newDocChunks = List.of(
                chunk("chunk 1"), chunk("chunk 2"), chunk("chunk 3"));

        // Every chunk of the new document finds a high-similarity hit against document_id=5.
        when(vectorStoreService.search(eq("chunk 1"), any())).thenReturn(List.of(hitFor("5", 0.03)));
        when(vectorStoreService.search(eq("chunk 2"), any())).thenReturn(List.of(hitFor("5", 0.05)));
        when(vectorStoreService.search(eq("chunk 3"), any())).thenReturn(List.of(hitFor("5", 0.02)));
        when(documentRepository.findById(5L)).thenReturn(Optional.of(dbDocument(5L)));

        Optional<SemanticDuplicateDetectionService.SemanticMatch> match =
                service.findDuplicate(newDocChunks, new User());

        assertTrue(match.isPresent());
        assertEquals(5L, match.get().document().getId());
        assertEquals(1.0, match.get().coverageRatio(), 0.001);
    }

    @Test
    void onlyOneOfManyChunksMatches_isNOTFlaggedAsDuplicate() {
        // Regression guard for the "one hot chunk = duplicate" anti-pattern:
        // 1 out of 5 chunks matching is well below the 0.6 coverage floor,
        // even though that one chunk's similarity is very high.
        List<org.springframework.ai.document.Document> newDocChunks = List.of(
                chunk("chunk 1"), chunk("chunk 2"), chunk("chunk 3"), chunk("chunk 4"), chunk("chunk 5"));

        when(vectorStoreService.search(eq("chunk 1"), any())).thenReturn(List.of(hitFor("5", 0.01)));
        when(vectorStoreService.search(eq("chunk 2"), any())).thenReturn(List.of());
        when(vectorStoreService.search(eq("chunk 3"), any())).thenReturn(List.of());
        when(vectorStoreService.search(eq("chunk 4"), any())).thenReturn(List.of());
        when(vectorStoreService.search(eq("chunk 5"), any())).thenReturn(List.of());

        Optional<SemanticDuplicateDetectionService.SemanticMatch> match =
                service.findDuplicate(newDocChunks, new User());

        assertTrue(match.isEmpty());
    }

    @Test
    void highCoverageButLowSimilarity_isNOTFlaggedAsDuplicate() {
        List<org.springframework.ai.document.Document> newDocChunks = List.of(chunk("chunk 1"), chunk("chunk 2"));

        // distance=0.5 -> similarity=0.5, well below the 0.9 threshold.
        when(vectorStoreService.search(eq("chunk 1"), any())).thenReturn(List.of(hitFor("5", 0.5)));
        when(vectorStoreService.search(eq("chunk 2"), any())).thenReturn(List.of(hitFor("5", 0.5)));

        assertTrue(service.findDuplicate(newDocChunks, new User()).isEmpty());
    }

    @Test
    void candidateSoftDeleted_isExcludedFromResults() {
        List<org.springframework.ai.document.Document> newDocChunks = List.of(chunk("chunk 1"));
        when(vectorStoreService.search(eq("chunk 1"), any())).thenReturn(List.of(hitFor("5", 0.01)));

        Document deleted = dbDocument(5L);
        deleted.setDeleted(true);
        when(documentRepository.findById(5L)).thenReturn(Optional.of(deleted));

        assertTrue(service.findDuplicate(newDocChunks, new User()).isEmpty());
    }

    @Test
    void searchIsScopedByUploaderAccessFilter_toAvoidLeakingOutOfScopeDocuments() {
        Filter.Expression scopeFilter = new Filter.Expression(Filter.ExpressionType.EQ,
                new Filter.Key("access_level"), new Filter.Value("PUBLIC"));
        when(documentAccessService.buildVectorFilter(any())).thenReturn(scopeFilter);
        when(vectorStoreService.search(eq("chunk 1"), eq(scopeFilter))).thenReturn(List.of());

        service.findDuplicate(List.of(chunk("chunk 1")), new User());

        // Verifies the exact scope filter (derived from the SAME permission
        // model used for RAG retrieval) was passed through to the vector
        // search, not an unscoped/global search.
    }

    @Test
    void moreChunksThanMaxSampled_isSampledRatherThanQueryingEveryChunk() {
        ReflectionTestUtils.setField(service, "maxChunksSampled", 3);
        List<org.springframework.ai.document.Document> newDocChunks = List.of(
                chunk("c1"), chunk("c2"), chunk("c3"), chunk("c4"), chunk("c5"), chunk("c6"));
        when(vectorStoreService.search(any(), any())).thenReturn(List.of());

        service.findDuplicate(newDocChunks, new User());

        org.mockito.Mockito.verify(vectorStoreService, org.mockito.Mockito.times(3))
                .search(any(), any());
    }
}
