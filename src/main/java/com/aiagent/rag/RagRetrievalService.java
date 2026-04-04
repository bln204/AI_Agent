package com.aiagent.rag;

import com.aiagent.model.User;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.service.AccessPolicyService;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RagRetrievalService {

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final AccessPolicyService accessPolicyService;

    private static final double SIMILARITY_THRESHOLD = 0.7;

    @Cacheable(value = "ai_retrieval", key = "#query + #user?.id")
    public List<Document> retrieveContext(String query, User user, Set<String> activeDocNames) {
        log.info("Retrieving context for query: '{}', user: {}", query, user != null ? user.getEmail() : "Guest");
        
        Filter.Expression authFilter = accessPolicyService.buildVectorFilter(user);
        List<Document> allResults = new ArrayList<>();

        // 1. Priority: Direct match in active documents (Session Context)
        if (activeDocNames != null && !activeDocNames.isEmpty()) {
            List<Document> step1 = performFilteredSearch(query, user, activeDocNames, 3, SIMILARITY_THRESHOLD);
            if (step1.size() >= 3) {
                log.debug("Early stop: Step 1 (Session Context) returned enough results ({})", step1.size());
                return processResults(step1);
            }
            allResults.addAll(step1);
        }

        // 2. Priority: DB-based Discovery (Keyword match on Title)
        String roleCode = (user != null && user.getRole() != null) ? user.getRole().getCode() : RoleConstants.ROLE_GUEST;
        Long deptId = (user != null && user.getDepartment() != null) ? user.getDepartment().getId() : -1L;
        Long userId = (user != null) ? user.getId() : -1L;
        String cleanedKeyword = cleanQuery(query);
        
        List<com.aiagent.model.Document> discoveredDocs = documentRepository.findCandidateDocuments(cleanedKeyword, roleCode, userId, deptId);
        if (!discoveredDocs.isEmpty()) {
            Set<String> discoveredDocNames = discoveredDocs.stream()
                    .map(com.aiagent.model.Document::getTitle)
                    .collect(Collectors.toSet());
            List<Document> step2 = performFilteredSearch(query, user, discoveredDocNames, 4, 0.6);
            
            // Early stop condition for Step 2: Clear quality match (>= 2 docs with decent score)
            boolean isQualityMatch = step2.size() >= 2 && step2.stream().anyMatch(d -> getScore(d) >= 0.7);
            if (isQualityMatch) {
                log.debug("Early stop: Step 2 (Discovery) quality match found ({})", step2.size());
                return processResults(step2);
            }
            allResults.addAll(step2);
        }

        // 3. Priority: Broad Search (Only if previous steps returned little/nothing)
        if (allResults.size() < 2) {
            log.debug("Performing broad search (T={})", 0.65);
            SearchRequest broadSearch = SearchRequest.query(query)
                    .withTopK(5)
                    .withSimilarityThreshold(0.65)
                    .withFilterExpression(authFilter);
            List<Document> step3 = vectorStore.similaritySearch(broadSearch);
            allResults.addAll(step3);
        }

        // 4. Final Fallback: Aggressive Broad Search for abstract queries
        if (allResults.isEmpty()) {
            log.debug("Performing aggressive broad search (T={})", 0.45);
            SearchRequest finalSearch = SearchRequest.query(query)
                    .withTopK(3)
                    .withSimilarityThreshold(0.45)
                    .withFilterExpression(authFilter);
            List<Document> step4 = vectorStore.similaritySearch(finalSearch);
            allResults.addAll(step4);
        }

        return processResults(allResults);
    }

    private List<Document> processResults(List<Document> results) {
        if (results == null || results.isEmpty()) return Collections.emptyList();

        // 1. Sort by score descending
        results.sort((a, b) -> Double.compare(getScore(b), getScore(a)));

        // 2. Deduplicate and focus on Diversity
        Map<String, Document> uniqueSources = new LinkedHashMap<>();
        List<Document> finalResults = new ArrayList<>();
        Set<String> seenContent = new HashSet<>();

        for (Document doc : results) {
            String content = doc.getContent().trim();
            if (seenContent.contains(content)) continue;
            seenContent.add(content);

            String source = (String) doc.getMetadata().getOrDefault("document_name", "unknown");
            if (!uniqueSources.containsKey(source)) {
                uniqueSources.put(source, doc);
                finalResults.add(doc);
            }
        }

        // Fill remaining slots with more chunks from already picked sources if needed
        if (finalResults.size() < 6) {
            for (Document doc : results) {
                if (finalResults.size() >= 6) break;
                if (!finalResults.contains(doc)) {
                    String content = doc.getContent().trim();
                    if (!seenContent.contains(content)) { // Content dedup
                         finalResults.add(doc);
                         seenContent.add(content);
                    }
                }
            }
        }

        return finalResults.stream().limit(6).collect(Collectors.toList());
    }

    private double getScore(Document doc) {
        Object score = doc.getMetadata().get("distance");
        if (score == null) score = doc.getMetadata().get("score");
        if (score instanceof Number) return ((Number) score).doubleValue();
        return 0.0;
    }


    private List<Document> performFilteredSearch(String query, User user, Set<String> docNames, int topK, double threshold) {
        Filter.Expression combinedFilter = buildCombinedFilter(user, new ArrayList<>(docNames));
        
        SearchRequest request = SearchRequest.query(query)
                .withTopK(topK)
                .withSimilarityThreshold(threshold)
                .withFilterExpression(combinedFilter);
        
        return vectorStore.similaritySearch(request);
    }

    private Filter.Expression buildCombinedFilter(User user, List<String> docNames) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op authOp = accessPolicyService.buildVectorFilterOp(user, b);
        
        FilterExpressionBuilder.Op nameFilterOp = b.in("document_name", docNames.toArray());
        
        if (authOp == null) {
            return nameFilterOp.build();
        }
        
        return b.and(nameFilterOp, b.group(authOp)).build();
    }

    private String cleanQuery(String query) {
        if (query == null) return "";
        // Remove common Vietnamese question noise to improve keyword matching
        String cleaned = query.toLowerCase()
            .replaceAll("như thế nào", "")
            .replaceAll("ra sao", "")
            .replaceAll("là gì", "")
            .replaceAll("gồm những gì", "")
            .replaceAll("của", "")
            .replaceAll("về", "")
            .replaceAll("cho", "")
            .replaceAll("\\?", "")
            .trim();
        
        // If query is too long, take the first 3-4 words as keywords
        String[] words = cleaned.split("\\s+");
        if (words.length > 5) {
            return String.join(" ", Arrays.copyOfRange(words, 0, 4));
        }
        return cleaned;
    }

}
