package com.aiagent.rag;

import com.aiagent.model.Document;
import com.aiagent.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RagReindexService {

    private final DocumentRepository documentRepository;
    private final DocumentIngestionService documentIngestionService;

    @Transactional(readOnly = true)
    public void reindexAllDocuments() {
        log.info("[RAG-REINDEX] Scanning database for documents to re-index...");
        
        List<Document> documents = documentRepository.findAllForReindexing();
        
        if (documents.isEmpty()) {
            log.info("[RAG-REINDEX] No documents found in database. Nothing to re-index.");
            return;
        }

        log.info("[RAG-REINDEX] Found {} documents. Starting sequential ingestion...", documents.size());

        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        for (Document doc : documents) {
            try {
                if (doc.getFilePath() == null || doc.getFilePath().trim().isEmpty()) {
                    log.warn("[RAG-REINDEX] Skipping doc ID {}: File path is missing.", doc.getId());
                    skipCount++;
                    continue;
                }

                String title = doc.getTitle().toLowerCase();
                if (title.contains("test") || title.contains("demo") || title.contains("sample")) {
                    log.info("[RAG-REINDEX] Skipping test/demo document: {}", doc.getTitle());
                    skipCount++;
                    continue;
                }

                java.io.File file = new java.io.File(doc.getFilePath());
                if (!file.exists()) {
                    log.warn("[RAG-REINDEX] Skipping doc ID {}: File not found at path: {}", doc.getId(), doc.getFilePath());
                    skipCount++;
                    continue;
                }

                log.info("[RAG-REINDEX] Processing [{}/{}]: ID={}, Title='{}'", 
                        (successCount + skipCount + errorCount + 1), documents.size(), doc.getId(), doc.getTitle());

                List<Long> deptIds = doc.getDepartments().stream()
                        .map(com.aiagent.model.Department::getId)
                        .collect(Collectors.toList());
                List<Long> projIds = doc.getProjects().stream()
                        .map(com.aiagent.model.Project::getId)
                        .collect(Collectors.toList());
                
                Long uploaderId = (doc.getUploadedBy() != null) ? doc.getUploadedBy().getId() : null;
                String userName = (doc.getUploadedBy() != null) ? doc.getUploadedBy().getUsername() : "UNKNOWN";
                String departmentNames = doc.getDepartments().stream()
                        .map(com.aiagent.model.Department::getName)
                        .collect(Collectors.joining(", "));
                if (departmentNames.isEmpty() && doc.getUploadedBy() != null && doc.getUploadedBy().getDepartment() != null) {
                    departmentNames = doc.getUploadedBy().getDepartment().getName();
                }
                
                // Fail-closed: fallback DEPARTMENT (PRIVATE scope removed) thay vì
                // PUBLIC nếu document thiếu accessLevel — kết hợp với departmentIds
                // rỗng, ingestDocumentSync's own guard sẽ ABORT thay vì lộ dữ liệu.
                String accessLevel = (doc.getAccessLevel() != null) ? doc.getAccessLevel().name() : "DEPARTMENT";

                documentIngestionService.ingestDocumentSync(
                        doc.getFilePath(), 
                        doc.getId(), 
                        doc.getDocumentUuid(),
                        doc.getTitle(), 
                        doc.getFileType(),
                        uploaderId, 
                        userName,
                        doc.getUploaderRole(),
                        departmentNames,
                        doc.getDecisionNumber(),
                        doc.getClassification() != null ? doc.getClassification().name() : "OTHER",
                        doc.getProjectName(),
                        doc.getDescription(),
                        doc.isInternalSourceFlag(),
                        accessLevel,
                        deptIds, 
                        projIds,
                        doc.getCreatedAt(),
                        doc.getVersion()
                );
                
                successCount++;
            } catch (Exception e) {
                log.error("[RAG-REINDEX] Error indexing doc ID {}: {}", doc.getId(), e.getMessage());
                errorCount++;
            }
        }

        log.info("[RAG-REINDEX] Batch re-indexing completed!");
        log.info("[RAG-REINDEX] Results: {} Success, {} Skipped, {} Errors.", successCount, skipCount, errorCount);
    }
}
