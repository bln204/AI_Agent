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
        
        List<Document> documents = documentRepository.findAll();
        log.info("[MAINTENANCE] Found {} documents to re-index.", documents.size());

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
                    doc.getVersion()
                );
                count++;
            } catch (Exception e) {
                log.error("[MAINTENANCE] Failed to re-index document ID {}: {}", doc.getId(), e.getMessage());
            }
        }

        return ResponseEntity.ok("Started re-indexing " + count + " documents in the background.");
    }
}
