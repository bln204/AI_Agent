package com.aiagent.rag;

import com.aiagent.model.User;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.service.AccessPolicyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RagRetrievalService {

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final AccessPolicyService accessPolicyService;
    private final QueryIntentClassifier queryIntentClassifier;
    private final org.springframework.cache.CacheManager cacheManager;

    @Value("${app.rag.semantic-top-k:25}")
    private int semanticTopK;

    @Value("${app.rag.anchored-top-k:10}")
    private int anchoredTopK;

    private static final int FINAL_TOP_K = 8;

    @SuppressWarnings("unchecked")
    public List<Document> retrieveContext(String query, User user, Set<String> activeDocNames) {
        String normalizedQuery = com.aiagent.util.NormalizationUtils.normalize(query);
        
        // 0. Query Understanding & Classification (Need this for accurate cache key)
        QueryIntentClassifier.ClassificationResult classification = queryIntentClassifier.classify(query, normalizedQuery);
        
        // CACHE CHECK (Waitstream 4: Manual Intent-Sensitive Caching)
        String cacheKey = com.aiagent.util.CacheKeyUtils.generateRetrievalKey(query, user, activeDocNames, classification.intent());
        org.springframework.cache.Cache cache = cacheManager.getCache("ai_retrieval");
        if (cache != null) {
            org.springframework.cache.Cache.ValueWrapper cached = cache.get(cacheKey);
            if (cached != null && cached.get() != null) {
                log.info("[RAG-CACHE] Hit for query: '{}', key: {}", query, cacheKey);
                return (List<Document>) cached.get();
            }
        }

        StopWatch sw = new StopWatch("RAG-Hybrid-Retrieval");
        sw.start("Initialization");
        
        log.info("[RAG-HYBRID] Starting retrieval for query: '{}' (normalized: '{}')", query, normalizedQuery);
        
        AccessPolicyService.SecurityScope scope = accessPolicyService.getSecurityScope(user);
        sw.stop();

        log.info("[RAG-HYBRID] Intent: {}, Tokens: {}, Scope: {}", 
                classification.intent(), classification.titleTokens(), scope.roleCode());

        List<Document> candidates = new ArrayList<>();

        // STEP A: Exact/Title-Aware Candidate Search
        sw.start("Step-A-TitleSearch");
        Set<String> searchTokens = classification.titleTokens();
        if (searchTokens.isEmpty() && normalizedQuery.length() > 3) {
            searchTokens = Set.of(normalizedQuery);
        }
        
        for (String token : searchTokens) {
            List<com.aiagent.model.Document> exactDocs = documentRepository.findByNormalizedTitle(token);
            if (exactDocs.isEmpty() && token.length() > 4) {
                exactDocs = documentRepository.findByNormalizedTitleContaining(token);
            }
            
            if (!exactDocs.isEmpty()) {
                log.info("[RAG-STEP-A] Title match found for token '{}': {} docs", token, exactDocs.size());
                Set<String> docNames = exactDocs.stream().map(com.aiagent.model.Document::getTitle).collect(Collectors.toSet());
                candidates.addAll(performFilteredSearch(query, user, docNames, 5, 0.45));
            }
        }
        sw.stop();

        // STEP B: Keyword Candidate Search (Quality Gated)
        sw.start("Step-B-KeywordSearch");
        if (normalizedQuery.length() > 4 || hasSpecialTokens(normalizedQuery)) {
            List<com.aiagent.model.Document> keywordDocs = documentRepository.findCandidateDocuments(
                    normalizedQuery, scope.roleCode(), scope.userId(), scope.departmentId());
            if (!keywordDocs.isEmpty()) {
                log.info("[RAG-STEP-B] Keyword DB matches: {}", keywordDocs.size());
                Set<String> docNames = keywordDocs.stream().map(com.aiagent.model.Document::getTitle).collect(Collectors.toSet());
                candidates.addAll(performFilteredSearch(query, user, docNames, 5, 0.50));
            }
        }
        sw.stop();

        // STEP C: Semantic Candidate Search (Adaptive Budget)
        sw.start("Step-C-SemanticSearch");
        boolean hasAnchoredHits = !candidates.isEmpty();
        int adaptiveTopK = (classification.intent() == QueryIntentClassifier.Intent.SPECIFIC_LOOKUP && hasAnchoredHits) 
                ? anchoredTopK : semanticTopK;
        
        log.info("[RAG-STEP-C] Performing semantic search (Top-K={}, anchored_context={})", adaptiveTopK, hasAnchoredHits);
        
        try {
            Filter.Expression authFilter = accessPolicyService.buildVectorFilter(user);
            SearchRequest broadRequest = SearchRequest.query(query)
                    .withTopK(adaptiveTopK)
                    .withSimilarityThreshold(0.40)
                    .withFilterExpression(authFilter);
            candidates.addAll(vectorStore.similaritySearch(broadRequest));
        } catch (Exception e) {
            log.error("[RAG-STEP-C-FAIL] broad semantic search failed, falling back to Step A/B candidates: {}", e.getMessage());
            if (candidates.isEmpty()) {
                throw new RuntimeException("RAG retrieval failed: major backend error and no cached/fallback candidates available.");
            }
        }
        sw.stop();

        // STEP D & E: Merge, Filter Quality & Security Verification
        sw.start("Step-D-E-Processing");
        List<Document> verifiedCandidates = candidates.stream()
                .filter(distinctByKey(d -> d.getMetadata().get("id") != null ? d.getMetadata().get("id") : d.getContent()))
                .filter(this::isHighQuality) // New: Quality Filter (Waitstream 3)
                .filter(hit -> verifySecurity(user, hit))
                .collect(Collectors.toList());
        
        log.info("[RAG-STEP-D/E] Candidates: merged={}, filtered_verified={}", candidates.size(), verifiedCandidates.size());

        // STEP F: Secure Reranking
        rerank(verifiedCandidates, classification, normalizedQuery, scope);
        sw.stop();

        // STEP G: Context Assembly
        sw.start("Step-G-Assembly");
        List<Document> finalContext = assembleContext(verifiedCandidates, classification, normalizedQuery);
        sw.stop();
        
        log.info("[RAG-METRICS] Retrieval Duration: {}ms, Candidates: {}, FinalContext: {}", 
                sw.getTotalTimeMillis(), verifiedCandidates.size(), finalContext.size());
        log.info("[RAG-DIAG] sw: {}", sw.prettyPrint());

        // CACHE FILL (Waitstream 4: Manual Intent-Sensitive Caching)
        if (cache != null && !finalContext.isEmpty()) {
            cache.put(cacheKey, finalContext);
        }

        return finalContext;
    }

    private boolean isHighQuality(Document doc) {
        String content = doc.getContent();
        if (content == null || content.isBlank()) return false;
        
        // 1. Minimum useful length (but don't drop solely on length)
        if (content.length() < 30) {
            // Check for keyword presence or alphanumeric density
            if (!content.matches(".*\\d{2,}.*") && !content.matches(".*[a-zA-Z]{3,}.*")) {
                return false; // drop purely non-alphanumeric short strings (noise)
            }
        }
        
        // 2. Generic Boilerplate Filter
        String lowerContent = content.toLowerCase();
        if (lowerContent.contains("copyright ©") || lowerContent.contains("all rights reserved")) {
            if (content.length() < 100) return false; // boilerplate only if short
        }
        if (lowerContent.matches("^page \\d+ of \\d+$")) return false;
        
        return true;
    }

    private boolean hasSpecialTokens(String s) {
        return s != null && s.matches(".*[0-9_-].*");
    }

    private static <T> java.util.function.Predicate<T> distinctByKey(java.util.function.Function<? super T, ?> keyExtractor) {
        Set<Object> seen = ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }

    private List<Document> assembleContext(List<Document> candidates, 
                                           QueryIntentClassifier.ClassificationResult classification,
                                           String normalizedQuery) {
        if (candidates.isEmpty()) return Collections.emptyList();

        QueryIntentClassifier.Intent intent = classification.intent();
        Set<String> titleTokens = classification.titleTokens();

        // 1. Dominant Document Identification (Selective Gating)
        String dominantDocId = null;
        boolean isAnchoredMatch = titleTokens.stream().anyMatch(token -> 
                candidates.stream().anyMatch(doc -> {
                    String docName = String.valueOf(doc.getMetadata().getOrDefault("document_name", ""));
                    if (isMojibake(docName)) return false; // Guard against garbage titles
                    
                    String normalizedName = com.aiagent.util.NormalizationUtils.normalize(docName);
                    String nameWithoutExt = stripExtension(normalizedName);
                    return nameWithoutExt.equals(token) || normalizedName.equals(token);
                })
        );

        if (intent == QueryIntentClassifier.Intent.SPECIFIC_LOOKUP && isAnchoredMatch) {
            dominantDocId = selectDominantDocument(candidates, titleTokens);
            if (dominantDocId != null) {
                log.info("[RAG-STABILIZE] Dominant document identified: {}", dominantDocId);
            }
        }

        // 2. Diversity Policy Settings
        int maxChunksPerDoc = (intent == QueryIntentClassifier.Intent.ABSTRACT_EXPLANATION) ? 2 : 4;
        int maxNoiseChunks = 2; // Limited noise when a dominant doc exists
        
        Map<String, Integer> docChunkCount = new HashMap<>();
        List<Document> finalContext = new ArrayList<>();
        int noiseCount = 0;

        for (Document doc : candidates) {
            if (finalContext.size() >= FINAL_TOP_K) break;
            
            Object idObj = doc.getMetadata().getOrDefault("document_id", "unknown");
            String currentDocId = String.valueOf(idObj);
            int currentDocChunks = docChunkCount.getOrDefault(currentDocId, 0);
            
            // Logic for Dominant vs Noise
            if (dominantDocId != null) {
                if (currentDocId.equals(dominantDocId)) {
                    // Current chunk is from dominant document
                    if (currentDocChunks < maxChunksPerDoc) {
                        doc.getMetadata().put("is_dominant", true);
                        finalContext.add(doc);
                        docChunkCount.put(currentDocId, currentDocChunks + 1);
                    }
                } else {
                    // Current chunk is noise
                    if (noiseCount < maxNoiseChunks && currentDocChunks < 1) {
                        finalContext.add(doc);
                        docChunkCount.put(currentDocId, currentDocChunks + 1);
                        noiseCount++;
                    }
                }
            } else {
                // Default behavior (Phase 1)
                if (currentDocChunks < maxChunksPerDoc) {
                    finalContext.add(doc);
                    docChunkCount.put(currentDocId, currentDocChunks + 1);
                }
            }
        }

        return finalContext;
    }

    private String selectDominantDocument(List<Document> documents, Set<String> titleTokens) {
        Map<String, Integer> docCounts = new HashMap<>();
        Map<String, Double> docBaseScoresSum = new HashMap<>();
        Map<String, String> docIdToName = new HashMap<>();

        for (Document doc : documents) {
            Object idObj = doc.getMetadata().getOrDefault("document_id", "unknown");
            String docId = String.valueOf(idObj);
            
            Object nameObj = doc.getMetadata().getOrDefault("document_name", "");
            String docName = com.aiagent.util.NormalizationUtils.normalize(String.valueOf(nameObj));
            
            docCounts.put(docId, docCounts.getOrDefault(docId, 0) + 1);
            docBaseScoresSum.put(docId, docBaseScoresSum.getOrDefault(docId, 0.0) + getScore(doc));
            docIdToName.put(docId, docName);
        }

        String bestDocId = null;
        double maxCompositeScore = -1.0;

        for (String docId : docCounts.keySet()) {
            double score = 0.0;
            String name = docIdToName.get(docId);
            String nameWithoutExt = stripExtension(name);
            
            double titleBoost = 0.0;
            // Signal 1: Exact Title Match (Strongest)
            if (titleTokens.stream().anyMatch(t -> nameWithoutExt.equals(t) || name.equals(t))) {
                titleBoost = 10.0;
            } else if (titleTokens.stream().anyMatch(t -> nameWithoutExt.contains(t) || name.contains(t))) {
                // Signal 2: Partial Title Match
                titleBoost = 5.0;
            }
            score += titleBoost;

            // Signal 3: Chunk Coverage (Presence)
            double coverageScore = docCounts.get(docId) * 1.0;
            score += coverageScore;

            // Signal 4: Aggregate Quality (Mean base score)
            double avgBaseScore = docBaseScoresSum.get(docId) / docCounts.get(docId);
            double qualityScore = avgBaseScore * 2.0;
            score += qualityScore;
            
            // Signal 5: Top-Ranked Chunk Bonus
            if (!documents.isEmpty()) {
                String topDocId = String.valueOf(documents.get(0).getMetadata().getOrDefault("document_id", ""));
                if (docId.equals(topDocId)) {
                    score += 3.0; // Bonus for owning the best chunk
                }
            }

            if (score > maxCompositeScore) {
                maxCompositeScore = score;
                bestDocId = docId;
            }
        }

        return bestDocId;
    }

    private String stripExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return fileName;
        return fileName.substring(0, fileName.lastIndexOf('.'));
    }

    private boolean isMojibake(String text) {
        if (text == null) return false;
        return text.contains("ß") || text.contains("├") || text.contains("┤") || text.contains("╗");
    }

    private void rerank(List<Document> documents, QueryIntentClassifier.ClassificationResult classification, 
                        String normalizedQuery, AccessPolicyService.SecurityScope scope) {
        
        documents.sort((a, b) -> {
            double scoreA = calculateBoostedScore(a, classification, normalizedQuery, scope);
            double scoreB = calculateBoostedScore(b, classification, normalizedQuery, scope);
            
            // Store the "boosted score" back into metadata for logging if needed, 
            // but here we just use it for sorting.
            return Double.compare(scoreB, scoreA);
        });
    }

    private double calculateBoostedScore(Document doc, QueryIntentClassifier.ClassificationResult classification, 
                                        String normalizedQuery, AccessPolicyService.SecurityScope scope) {
        
        double baseScore = getScore(doc);
        double boost = 0.0;
        
        String docName = com.aiagent.util.NormalizationUtils.normalize((String) doc.getMetadata().getOrDefault("document_name", ""));
        String docProjectId = (String) doc.getMetadata().get("project_id");

        // 1. Title/Token Match Boost
        for (String token : classification.titleTokens()) {
            if (docName.equals(token)) {
                boost += 2.0; // Huge boost for exact title match
            } else if (docName.contains(token)) {
                boost += 1.0;
            }
        }

        // 2. Project Match Boost
        if (docProjectId != null && scope.projectIds().stream().anyMatch(id -> id.toString().equals(docProjectId))) {
            boost += 0.5;
        }

        // Adjust weights based on intent
        switch (classification.intent()) {
            case SPECIFIC_LOOKUP:
                // Highly value title matches (Gated high boost)
                double lookupBoost = 0.0;
                for (String token : classification.titleTokens()) {
                    if (docName.equals(token)) lookupBoost += 5.0;
                    else if (docName.contains(token)) lookupBoost += 3.0;
                }
                return baseScore + lookupBoost + (boost * 0.5); // Still include small general boost
            case ABSTRACT_EXPLANATION:
                // Value semantic relevance more, title boost is secondary
                return baseScore + (boost * 0.5);
            case MIXED:
            default:
                return baseScore + boost;
        }
    }

    private boolean verifySecurity(User user, Document hit) {
        String docIdStr = (String) hit.getMetadata().get("document_id");
        if (docIdStr == null) return false;
        
        try {
            Long docId = Long.parseLong(docIdStr);
            Optional<com.aiagent.model.Document> docEntity = documentRepository.findById(docId);
            if (docEntity.isEmpty()) {
                log.warn("[RAG-SECURITY] Hit skipped - document id {} not found in DB", docId);
                return false;
            }
            
            boolean allowed = accessPolicyService.canAccessDocument(user, docEntity.get());
            if (!allowed) {
                log.error("[RAG-SECURITY-LEAK-PREVENTED] Unauthorized hit blocked for user {}: doc_id={}, title='{}'", 
                        user != null ? user.getEmail() : "Guest", docId, docEntity.get().getTitle());
            }
            return allowed;
        } catch (Exception e) {
            log.error("[RAG-SECURITY] Error checking security for hit: {}", e.getMessage());
            return false;
        }
    }

    private double getScore(Document doc) {
        Object score = doc.getMetadata().get("score");
        if (score == null) score = doc.getMetadata().get("distance");
        if (score instanceof Number) return ((Number) score).doubleValue();
        return 0.0;
    }

    private List<Document> performFilteredSearch(String query, User user, Set<String> docNames, int topK, double threshold) {
        List<String> sanitizedNames = (docNames != null) ?
            docNames.stream()
                .filter(java.util.Objects::nonNull)
                .filter(name -> !name.isBlank())
                .limit(50)
                .collect(Collectors.toList())
            : Collections.emptyList();

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op authOp = accessPolicyService.buildVectorFilterOp(user, b);
        FilterExpressionBuilder.Op nameFilterOp = null;
        
        if (!sanitizedNames.isEmpty()) {
            nameFilterOp = b.in("document_name", sanitizedNames.toArray(new String[0]));
        }
        
        Filter.Expression combinedFilter = (authOp == null) ? 
                (nameFilterOp != null ? nameFilterOp.build() : null) :
                (nameFilterOp == null ? authOp.build() : b.and(nameFilterOp, b.group(authOp)).build());
        
        SearchRequest request = SearchRequest.query(query)
                .withTopK(topK)
                .withSimilarityThreshold(threshold)
                .withFilterExpression(combinedFilter);
        
        try {
            return vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.error("Qdrant search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
