package com.aiagent.controller;

import com.aiagent.model.Document;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.rag.DocumentIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping(value = "/api/maintenance", produces = "application/json;charset=UTF-8")
@RequiredArgsConstructor
@Slf4j
public class MaintenanceController {

    private final DocumentRepository documentRepository;
    private final DocumentIngestionService ingestionService;

    @PostMapping("/reindex")
    @PreAuthorize("hasRole('DIRECTOR')")
    public ResponseEntity<String> reindexAll(
            @RequestParam(required = false, defaultValue = "company_documents") String collectionName,
            Authentication authentication) {
        log.info("[MAINTENANCE] Re-index into collection '{}' triggered by: {}",
                collectionName, authentication != null ? authentication.getName() : "UNKNOWN");
        
        // Must stay in sync with the approval gate in DocumentService: a
        // PENDING_APPROVAL/REJECTED document must never reach Qdrant, and a
        // manual reindex is exactly the kind of "second path" that could leak
        // one in if it used findAll() here instead of the status-filtered
        // query DocumentRepository already exposes.
        List<Document> documents = documentRepository.findAllForReindexing();
        log.info("[MAINTENANCE] Found {} APPROVED documents to re-index.", documents.size());

        int count = 0;
        for (Document doc : documents) {
            try {
                List<Long> deptIds = doc.getDepartments().stream().map(d -> d.getId()).collect(Collectors.toList());
                List<Long> projIds = doc.getProjects().stream().map(p -> p.getId()).collect(Collectors.toList());
                
                String userName = (doc.getUploadedBy() != null) ? doc.getUploadedBy().getUsername() : "UNKNOWN";
                String departmentNames = doc.getDepartments().stream()
                        .map(com.aiagent.model.Department::getName)
                        .collect(Collectors.joining(", "));
                if (departmentNames.isEmpty() && doc.getUploadedBy() != null && doc.getUploadedBy().getDepartment() != null) {
                    departmentNames = doc.getUploadedBy().getDepartment().getName();
                }
                String approverName = (doc.getApprovedBy() != null) ? doc.getApprovedBy().getUsername() : null;

                ingestionService.ingestDocument(
                    doc.getFilePath(),
                    doc.getId(),
                    doc.getDocumentUuid(),
                    doc.getTitle(),
                    doc.getFileType(),
                    doc.getUploadedBy().getId(),
                    userName,
                    doc.getUploaderRole(),
                    departmentNames,
                    doc.getDecisionNumber(),
                    doc.getClassification() != null ? doc.getClassification().name() : "OTHER",
                    doc.getProjectName(),
                    doc.getDescription(),
                    doc.isInternalSourceFlag(),
                    doc.getAccessLevel().name(),
                    deptIds,
                    projIds,
                    doc.getCreatedAt(),
                    doc.getVersion(),
                    approverName,
                    doc.getApprovedAt()
                );
                count++;
            } catch (Exception e) {
                log.error("[MAINTENANCE] Failed to re-index document ID {}: {}", doc.getId(), e.getMessage());
            }
        }

        return ResponseEntity.ok("Started re-indexing " + count + " documents in the background.");
    }

    /**
     * Removes Qdrant vectors left behind by documents that no longer exist in
     * the database (e.g. deleted directly, or before deletion always cleaned
     * up vectors) — HydrationService already detects and drops these
     * "orphan vector" chunks at query time (RAG security barrier), but they
     * still consume Top-K search slots before being dropped, crowding out
     * legitimate results. Guarded to only ever purge an ID that does NOT
     * exist in the documents table, so a live document's vectors can never
     * be deleted through this endpoint even if a wrong ID is passed.
     */
    @PostMapping("/purge-orphans")
    @PreAuthorize("hasRole('DIRECTOR')")
    public ResponseEntity<String> purgeOrphanVectors(
            @RequestParam List<Long> documentIds,
            Authentication authentication) {
        log.info("[MAINTENANCE] Purge orphan vectors for {} document ID(s) triggered by: {}",
                documentIds.size(), authentication != null ? authentication.getName() : "UNKNOWN");

        int purged = 0;
        int skipped = 0;
        for (Long id : documentIds) {
            if (documentRepository.existsById(id)) {
                log.warn("[MAINTENANCE] Skipping purge for document ID {} — it still exists in the database.", id);
                skipped++;
                continue;
            }
            ingestionService.deleteFromVectorStore(id);
            purged++;
        }

        return ResponseEntity.ok("Purged vectors for " + purged + " orphaned document ID(s), skipped " + skipped + " still-existing document ID(s).");
    }
}
