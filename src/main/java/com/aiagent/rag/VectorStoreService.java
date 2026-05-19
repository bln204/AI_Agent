package com.aiagent.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class VectorStoreService {

    private final VectorStore vectorStore;

    @Value("${app.rag.similarity-threshold:0.3}")
    private double defaultThreshold;

    @Value("${app.rag.top-k:10}")
    private int topK;

    public List<Document> search(String query, Filter.Expression filterExpression) {
        log.info("[VECTOR-SEARCH] Initializing search for query: '{}', threshold: {}", query, defaultThreshold);

        SearchRequest request = SearchRequest.query(query)
                .withTopK(topK)
                .withSimilarityThreshold(defaultThreshold)
                .withFilterExpression(filterExpression);

        List<Document> results = vectorStore.similaritySearch(request);

        if (results.isEmpty()) {
            log.warn("[VECTOR-SEARCH] No documents found above threshold {}", defaultThreshold);
        } else {
            log.info("[VECTOR-SEARCH] Found {} documents above threshold.", results.size());
            for (int i = 0; i < results.size(); i++) {
                Document doc = results.get(i);
                double rawScore = getScore(doc);
                
                double similarity = 1.0 - rawScore; 
                if (similarity < 0) similarity = 0;
                if (similarity > 1) similarity = 1;

                String docId = (String) doc.getMetadata().getOrDefault("document_id", "null");
                String source = (String) doc.getMetadata().getOrDefault("source", "unknown");

                log.info("[VECTOR-SEARCH] Result #{} | Similarity: {} | RawDist: {} | DocID: {} | Source: '{}'",
                        i + 1, String.format("%.4f", similarity), String.format("%.4f", rawScore), docId, source);
            }
        }

        return results;
    }

    private double getScore(Document doc) {
        Object score = doc.getMetadata().get("score");
        if (score == null)
            score = doc.getMetadata().get("distance");
        if (score instanceof Number n)
            return n.doubleValue();
        return 0.0;
    }
}
