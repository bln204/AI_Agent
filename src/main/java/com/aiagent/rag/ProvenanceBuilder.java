package com.aiagent.rag;

import lombok.Builder;
import lombok.Data;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class ProvenanceBuilder {

    @Data
    @Builder
    public static class SourceMetadata {
        private String documentId;
        private String documentName;
        private String source;
        private String uploadDate;
        private String decisionNumber;
        private String userName;
        private String uploaderRole;
        private String department;
        private String projectName;
        private boolean internalSource;
        private double score;
    }

    /**
     * Extracts and deduplicates metadata from retrieved documents.
     * Filters out null document IDs and keeps the highest score for each document.
     */
    public List<SourceMetadata> buildProvenanceData(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, SourceMetadata> uniqueSources = new HashMap<>();

        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            String docId = String.valueOf(metadata.get("document_id"));
            
            // 1. Handle potential null document_id to avoid runtime errors
            if (docId == null || "null".equals(docId) || docId.isBlank()) {
                continue; // Skip documents without valid ID
            }

            double currentScore = getScore(doc);
            
            if (!uniqueSources.containsKey(docId) || currentScore > uniqueSources.get(docId).getScore()) {
                SourceMetadata data = SourceMetadata.builder()
                        .documentId((String) metadata.getOrDefault("document_uuid", metadata.getOrDefault("document_id", "UNKNOWN")))
                        .documentName((String) metadata.getOrDefault("document_name", metadata.getOrDefault("source", "UNKNOWN")))
                        .source((String) metadata.getOrDefault("source", "UNKNOWN"))
                        .uploadDate((String) metadata.getOrDefault("upload_date", "UNKNOWN"))
                        .decisionNumber((String) metadata.getOrDefault("decision_number", metadata.getOrDefault("decision", "N/A")))
                        .userName((String) metadata.getOrDefault("user_name", "UNKNOWN"))
                        .uploaderRole((String) metadata.getOrDefault("uploader_role", "UNKNOWN"))
                        .department((String) metadata.getOrDefault("department", "UNKNOWN"))
                        .projectName((String) metadata.getOrDefault("project_name", "N/A"))
                        .internalSource(Boolean.parseBoolean(String.valueOf(metadata.getOrDefault("internal_source_flag", "true"))))
                        .score(currentScore)
                        .build();
                uniqueSources.put(docId, data);
            }
        }

        // Return sorted by score (descending), limited to top 3
        return uniqueSources.values().stream()
                .sorted(Comparator.comparing(SourceMetadata::getScore).reversed())
                .limit(3)
                .collect(Collectors.toList());
    }

    private double getScore(Document doc) {
        Object score = doc.getMetadata().get("score");
        if (score == null) score = doc.getMetadata().get("distance");
        if (score instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
