package com.aiagent.rag;

import com.aiagent.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RagService {

    private final VectorStoreService vectorStoreService;
    private final com.aiagent.rag.retrieval.HybridRetrievalService hybridRetrievalService;
    private final HydrationService hydrationService;
    private final PromptBuilder promptBuilder;
    private final ProvenanceBuilder provenanceBuilder;
    private final ChatModel chatModel;

    @Transactional(readOnly = true)
    public String processQuery(String question, User user, Filter.Expression filter, List<com.aiagent.rag.analyzer.DetectedEntity> entities, String historyText) {
        List<Document> rawDocuments = hybridRetrievalService.search(question, filter, entities);

        log.info("[RAG-PIPELINE] Validating {} candidates through hydration barrier...", rawDocuments.size());
        List<Document> validDocuments = hydrationService.hydrateAndValidate(rawDocuments, user);

        if (validDocuments.isEmpty()) {
            if (!rawDocuments.isEmpty()) {
                log.warn("[RAG-PIPELINE] Retrieval found {} chunks, but ALL were filtered by security barrier for user {}", 
                        rawDocuments.size(), user.getEmail());
                return "Mình tìm thấy một vài tài liệu có vẻ liên quan đến câu hỏi này, nhưng chúng không nằm trong phạm vi "
                        + "truy cập của bạn, nên mình không thể dùng để trả lời. Nếu bạn cần xem, hãy liên hệ người quản lý "
                        + "tài liệu hoặc quản trị viên để được cấp quyền nhé.";
            }
            return promptBuilder.getFallbackMessage();
        }
        
        List<Document> highQualityDocuments = validDocuments.stream()
                .filter(doc -> doc.getContent() != null && doc.getContent().trim().length() >= 50)
                .collect(Collectors.toList());
        
        if (highQualityDocuments.isEmpty()) {
            log.warn("[RAG-PIPELINE] All retrieved chunks are below quality threshold (50 chars). Proceeding with original valid documents as fallback.");
            highQualityDocuments = validDocuments;
        }

        log.info("[RAG-PIPELINE] Applying Balanced Top-K Deduplication (Round-Robin) on {} quality chunks...", highQualityDocuments.size());
        Map<String, List<Document>> docsBySource = highQualityDocuments.stream()
                .collect(Collectors.groupingBy(
                        doc -> (String) doc.getMetadata().getOrDefault("document_id", "unknown"),
                        java.util.LinkedHashMap::new,
                        Collectors.toList()
                ));

        java.util.List<Document> deduplicatedDocs = new java.util.ArrayList<>();
        int maxChunksPerDoc = 3;
        int totalLimit = 10;
        
        boolean added;
        int round = 0;
        do {
            added = false;
            for (String docId : docsBySource.keySet()) {
                List<Document> chunks = docsBySource.get(docId);
                if (round < chunks.size() && round < maxChunksPerDoc && deduplicatedDocs.size() < totalLimit) {
                    deduplicatedDocs.add(chunks.get(round));
                    added = true;
                }
            }
            round++;
        } while (added && deduplicatedDocs.size() < totalLimit);

        String context = deduplicatedDocs.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));
        log.info("[RAG-CONTEXT] Final context string length: {} chars", context.length());
        List<ProvenanceBuilder.SourceMetadata> sources = provenanceBuilder.buildProvenanceData(deduplicatedDocs);
        String provenanceString = formatProvenance(sources);

        log.info("[RAG-PIPELINE] Calling LLM with {} validated chunks (deduplicated)...", deduplicatedDocs.size());
        org.springframework.ai.chat.prompt.Prompt prompt = promptBuilder.buildPrompt(question, context, historyText, provenanceString);
        log.info("[VERIFY-PROMPT] Final prompt size: {} characters", prompt.getContents().length());

        try {
            String aiAnswer = chatModel.call(prompt).getResult().getOutput().getContent();
            if (aiAnswer != null && !aiAnswer.trim().isEmpty()) {
                logFinalMetrics(deduplicatedDocs, context.length(), true);
                return aiAnswer.trim();
            }
        } catch (Exception e) {
            log.error("[RAG-PIPELINE] LLM call failed: {}", e.getMessage(), e);
        }

        log.warn("[RAG-FAILSAFE] LLM unavailable, returning safe fallback response");
        logFinalMetrics(deduplicatedDocs, context.length(), false);
        return buildFailsafeResponse(sources);
    }

    private void logFinalMetrics(List<Document> docs, int contextChars, boolean success) {
        log.info("[RAG-METRICS] Pipeline end. Success={}, Chunks={}, ContextSize={} chars", 
                success, docs.size(), contextChars);
    }

    private String buildFailsafeResponse(List<ProvenanceBuilder.SourceMetadata> sources) {
        StringBuilder message = new StringBuilder(
                "Hệ thống AI hiện đang quá tải nên chưa thể xử lý câu hỏi của bạn ngay lúc này. "
                        + "Bạn vui lòng thử lại sau vài giây hoặc liên hệ quản trị viên nếu tình trạng này tiếp tục xảy ra nhé.");

        if (!sources.isEmpty()) {
            message.append(" Trong lúc chờ, mình đã tìm thấy một vài tài liệu có thể liên quan: ");
            for (int i = 0; i < sources.size(); i++) {
                ProvenanceBuilder.SourceMetadata source = sources.get(i);
                if (i > 0) message.append("; ");
                message.append('"').append(source.getDocumentName()).append('"')
                        .append(" (do ").append(source.getUserName());

                String department = source.getDepartment();
                boolean hasRealDepartment = department != null && !department.isBlank()
                        && !"UNKNOWN".equalsIgnoreCase(department) && !"Tất cả".equalsIgnoreCase(department);
                if (hasRealDepartment) {
                    message.append(" thuộc ").append(department);
                }

                // Director uploads are self-approved (uploader == approver) -- citing the
                // approver separately would just repeat the same name, see triggerIngestion().
                boolean selfApproved = source.getUserName() != null
                        && source.getUserName().equals(source.getApproverName());
                message.append(" tải lên");
                if (!selfApproved) {
                    message.append(", ").append(source.getApproverName()).append(" duyệt");
                }
                message.append(')');
            }
            message.append('.');
        }

        return message.toString();
    }


    private String formatProvenance(List<ProvenanceBuilder.SourceMetadata> sources) {
        if (sources.isEmpty()) {
            return "Không có nguồn dữ liệu.";
        }

        StringBuilder sb = new StringBuilder();
        for (ProvenanceBuilder.SourceMetadata source : sources) {
            sb.append("- DOCUMENT: ").append(source.getDocumentName())
              .append(" | DECISION: ").append(source.getDecisionNumber().equals("N/A") ? "None" : source.getDecisionNumber())
              .append(" | UPLOADER: ").append(source.getUserName())
              .append(" | UPLOADER_ROLE: ").append(source.getUploaderRole())
              .append(" | DEPT: ").append(source.getDepartment())
              .append(" | PROJECT: ").append(source.getProjectName())
              .append(" | UPLOAD_DATE: ").append(source.getUploadDate())
              .append(" | APPROVER: ").append(source.getApproverName())
              .append(" | APPROVER_ROLE: ").append(source.getApproverRole())
              .append(" | APPROVED_DATE: ").append(source.getApprovedDate());
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String buildFinalResponse(String answer, String provenance) {
        return "PHẦN 1:\n" + answer + "\n\nPHẦN 2:\n" + provenance;
    }
}
