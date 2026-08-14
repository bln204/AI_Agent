package com.aiagent.rag.retrieval;

import com.aiagent.rag.VectorStoreService;
import com.aiagent.rag.analyzer.DetectedEntity;
import com.aiagent.rag.analyzer.EntityType;
import com.aiagent.rag.filter.MetadataFilterBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the fallback added to HybridRetrievalService: when an entity/row
 * filter (e.g. ROW_VALUE from an unverified XLSX row lookup) narrows the
 * vector search to zero results, retrieval must retry with pure semantic
 * search under the security filter alone rather than returning nothing —
 * this guards against a mis-extracted candidate silently breaking retrieval
 * for otherwise-answerable questions.
 */
class HybridRetrievalServiceTest {

    private VectorStoreService vectorStoreService;
    private HybridRetrievalService service;

    private final Filter.Expression securityFilter = new FilterExpressionBuilder().eq("access_level", "PUBLIC").build();

    @BeforeEach
    void setUp() {
        vectorStoreService = mock(VectorStoreService.class);
        service = new HybridRetrievalService(vectorStoreService, new MetadataFilterBuilder());
    }

    private Document doc(String content) {
        return new Document(content, Map.of());
    }

    @Test
    void noEntities_singleCallWithSecurityFilterOnly() {
        when(vectorStoreService.search(anyString(), eq(securityFilter))).thenReturn(List.of(doc("a")));

        List<Document> results = service.search("hello", securityFilter, List.of());

        assertEquals(1, results.size());
        verify(vectorStoreService, times(1)).search(anyString(), any());
    }

    @Test
    void entityFilterFindsResults_noFallbackTriggered() {
        when(vectorStoreService.search(anyString(), any())).thenReturn(List.of(doc("NV0003 row")));

        List<DetectedEntity> entities = List.of(new DetectedEntity(EntityType.ROW_VALUE, "NV0003"));
        List<Document> results = service.search("Nhân viên NV0003 là ai?", securityFilter, entities);

        assertEquals(1, results.size());
        // Only the combined (entity + security) filter call — no second, broader call.
        verify(vectorStoreService, times(1)).search(anyString(), any());
    }

    @Test
    void entityFilterReturnsEmpty_fallsBackToPureSemanticSearch() {
        Filter.Expression entityFilter = new MetadataFilterBuilder()
                .build(List.of(new DetectedEntity(EntityType.ROW_VALUE, "NV9999")));
        Filter.Expression combined = new Filter.Expression(Filter.ExpressionType.AND, securityFilter, entityFilter);

        when(vectorStoreService.search(anyString(), eq(combined))).thenReturn(List.of());
        when(vectorStoreService.search(anyString(), eq(securityFilter))).thenReturn(List.of(doc("fallback result")));

        List<DetectedEntity> entities = List.of(new DetectedEntity(EntityType.ROW_VALUE, "NV9999"));
        List<Document> results = service.search("Nhân viên NV9999 là ai?", securityFilter, entities);

        assertEquals(1, results.size());
        assertEquals("fallback result", results.get(0).getContent());
        verify(vectorStoreService, times(2)).search(anyString(), any());
    }

    @Test
    void noEntities_emptyResult_doesNotTriggerFallbackSearch() {
        when(vectorStoreService.search(anyString(), eq(securityFilter))).thenReturn(List.of());

        List<Document> results = service.search("random question", securityFilter, List.of());

        assertEquals(0, results.size());
        // No entity filter was ever built, so there is nothing to fall back from —
        // must not double-search.
        verify(vectorStoreService, times(1)).search(anyString(), any());
    }
}
