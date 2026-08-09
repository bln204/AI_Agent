package com.aiagent.rag.retrieval;

import com.aiagent.rag.VectorStoreService;
import com.aiagent.rag.analyzer.DetectedEntity;
import com.aiagent.rag.filter.MetadataFilterBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class HybridRetrievalService {

    private final VectorStoreService vectorStoreService;
    private final MetadataFilterBuilder filterBuilder;

    public List<Document> search(String question, Filter.Expression securityFilter, List<DetectedEntity> entities) {
        Filter.Expression entityFilter = filterBuilder.build(entities);
        
        Filter.Expression finalFilter;
        if (entityFilter != null) {
            // Apply Entity Filter within Security constraints
            if (securityFilter != null) {
                finalFilter = new Filter.Expression(Filter.ExpressionType.AND, securityFilter, entityFilter);
            } else {
                finalFilter = entityFilter;
            }
            log.info("[HYBRID] Using Entity + Security Filter");
        } else {
            finalFilter = securityFilter;
            log.info("[HYBRID] No entities found, using Pure Semantic Search with Security Filter");
        }

        log.info("[HYBRID] Applied Metadata Filter: {}", finalFilter);

        List<Document> results = vectorStoreService.search(question, finalFilter);
        
        int resultCount = (results != null) ? results.size() : 0;
        
        log.info("[HYBRID] Metadata Search Result Count: {}", (entityFilter != null ? resultCount : "N/A"));
        log.info("[HYBRID] Semantic Search Result Count: {}", resultCount);
        log.info("[HYBRID] Merged Result Count: {}", resultCount);

        if (results != null && !results.isEmpty()) {
            for (int i = 0; i < results.size(); i++) {
                Document doc = results.get(i);
                String docId = (String) doc.getMetadata().getOrDefault("document_id", "unknown");
                log.info("[VECTOR] Retrieved Chunk #{} | DocID: {} | ContentLength: {} chars",
                    i + 1, docId, doc.getContent().length());
            }
        }
        
        return results;
    }
}
