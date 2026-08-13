package com.aiagent.rag;

import com.aiagent.model.Document;
import com.aiagent.model.User;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.service.DocumentAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Level 3 duplicate detection: decides whether a not-yet-persisted document's
 * chunks are, in aggregate, semantically the same document as one already
 * indexed in Qdrant.
 *
 * Deliberately NOT a "one hot chunk = duplicate" check (see WORKING_RULES /
 * task spec): a candidate is only flagged when a high enough FRACTION of the
 * new document's own chunks (coverageRatio) each find a high-similarity match
 * against the SAME existing document_id, with the average similarity of those
 * matches also above threshold. A single similar paragraph in an otherwise
 * different document will have low coverage and will not trigger a match.
 *
 * Candidates are found via DocumentAccessService#buildVectorFilter(uploader),
 * i.e. the SAME permission-scoped filter used for RAG retrieval — this is
 * what keeps the check from ever comparing against (or revealing the
 * existence of) a document the uploader has no access to.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SemanticDuplicateDetectionService {

    private final VectorStoreService vectorStoreService;
    private final DocumentAccessService documentAccessService;
    private final DocumentRepository documentRepository;

    @Value("${app.document.duplicate.semantic-similarity-threshold:0.92}")
    private double similarityThreshold;

    @Value("${app.document.duplicate.semantic-min-coverage-ratio:0.6}")
    private double minCoverageRatio;

    @Value("${app.document.duplicate.semantic-max-chunks-sampled:20}")
    private int maxChunksSampled;

    public record SemanticMatch(Document document, double avgSimilarity, double coverageRatio) {}

    public Optional<SemanticMatch> findDuplicate(List<org.springframework.ai.document.Document> newDocumentChunks, User uploader) {
        List<org.springframework.ai.document.Document> sampled = sample(newDocumentChunks);
        if (sampled.isEmpty()) {
            return Optional.empty();
        }

        Filter.Expression scopeFilter = documentAccessService.buildVectorFilter(uploader);

        Map<String, List<Double>> similaritiesByDocId = new HashMap<>();
        for (org.springframework.ai.document.Document chunk : sampled) {
            String text = chunk.getContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            List<org.springframework.ai.document.Document> hits = vectorStoreService.search(text, scopeFilter);
            for (org.springframework.ai.document.Document hit : hits) {
                double similarity = similarityOf(hit);
                if (similarity < similarityThreshold) {
                    continue;
                }
                Object docIdRaw = hit.getMetadata().get("document_id");
                if (docIdRaw == null) {
                    continue;
                }
                similaritiesByDocId.computeIfAbsent(String.valueOf(docIdRaw), k -> new ArrayList<>()).add(similarity);
            }
        }

        int totalSampled = sampled.size();
        String bestDocId = null;
        double bestAvg = 0.0;
        double bestCoverage = 0.0;

        for (Map.Entry<String, List<Double>> entry : similaritiesByDocId.entrySet()) {
            List<Double> sims = entry.getValue();
            double coverage = (double) sims.size() / totalSampled;
            double avg = sims.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

            if (coverage >= minCoverageRatio && avg >= similarityThreshold && coverage > bestCoverage) {
                bestDocId = entry.getKey();
                bestAvg = avg;
                bestCoverage = coverage;
            }
        }

        if (bestDocId == null) {
            return Optional.empty();
        }

        log.info("[DUPLICATE-SEMANTIC] candidate document_id={}, avgSimilarity={}, coverageRatio={}, sampledChunks={}",
                bestDocId, String.format("%.4f", bestAvg), String.format("%.2f", bestCoverage), totalSampled);

        final double finalAvg = bestAvg;
        final double finalCoverage = bestCoverage;
        try {
            Long id = Long.parseLong(bestDocId);
            return documentRepository.findById(id)
                    .filter(d -> !d.isDeleted())
                    .map(d -> new SemanticMatch(d, finalAvg, finalCoverage));
        } catch (NumberFormatException e) {
            log.warn("[DUPLICATE-SEMANTIC] Non-numeric document_id in vector metadata: {}", bestDocId);
            return Optional.empty();
        }
    }

    private List<org.springframework.ai.document.Document> sample(List<org.springframework.ai.document.Document> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        if (chunks.size() <= maxChunksSampled || maxChunksSampled <= 0) {
            return chunks;
        }
        List<org.springframework.ai.document.Document> result = new ArrayList<>(maxChunksSampled);
        double stride = (double) chunks.size() / maxChunksSampled;
        for (int i = 0; i < maxChunksSampled; i++) {
            result.add(chunks.get((int) (i * stride)));
        }
        return result;
    }

    private double similarityOf(org.springframework.ai.document.Document doc) {
        Object score = doc.getMetadata().get("score");
        if (score == null) {
            score = doc.getMetadata().get("distance");
        }
        double raw = (score instanceof Number n) ? n.doubleValue() : 0.0;
        double similarity = 1.0 - raw;
        if (similarity < 0) similarity = 0;
        if (similarity > 1) similarity = 1;
        return similarity;
    }
}
