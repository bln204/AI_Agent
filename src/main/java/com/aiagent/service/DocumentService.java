package com.aiagent.service;

import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.User;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.ProjectRepository;
import com.aiagent.rag.DocumentIngestionService;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt", "xlsx");

    private static final Map<String, String> ALLOWED_MIME_TYPE_BY_EXTENSION = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "txt", "text/plain",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    // Định dạng LibreOffice headless convert được sang PDF cho Document Viewer
    // (xem DocumentViewerConversionService). PDF không cần convert (dùng file gốc).
    private static final Set<String> VIEWER_CONVERTIBLE_EXTENSIONS = Set.of("DOCX", "TXT", "XLSX");

    private final DocumentRepository documentRepository;
    private final DocumentIngestionService documentIngestionService;
    private final DepartmentRepository departmentRepository;
    private final ProjectRepository projectRepository;
    private final DocumentAccessService documentAccessService;
    private final DocumentViewerConversionService documentViewerConversionService;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Value("${app.upload.max-size-mb:50}")
    private long maxUploadSizeMb;

    public Page<Document> getAccessibleDocumentsPaginated(User user, String keyword, Pageable pageable) {
        String normalizedKeyword = null;
        if (keyword != null && !keyword.trim().isEmpty()) {
            String temp = java.text.Normalizer.normalize(keyword.trim(), java.text.Normalizer.Form.NFD);
            normalizedKeyword = temp.replaceAll("\\p{M}", "").toLowerCase();
        }

        if (user == null) {
            log.debug("[DOC-ACCESS] user=null → returning PUBLIC docs only, keyword={}", normalizedKeyword);
            return documentRepository.findPublicDocuments(normalizedKeyword, pageable);
        }

        String roleCode = user.getRole() != null ? user.getRole().getCode() : RoleConstants.ROLE_GUEST;
        Long userId = user.getId();
        Long deptId = user.getDepartment() != null ? user.getDepartment().getId() : null;

        log.debug("[DOC-ACCESS] userId={}, roleCode={}, deptId={}, keyword={}", userId, roleCode, deptId, normalizedKeyword);

        if (RoleConstants.ROLE_DIRECTOR.equals(roleCode)) {
            log.debug("[DOC-ACCESS] DIRECTOR path → returning ALL docs");
            return documentRepository.findAllAccessibleForDirector(normalizedKeyword, pageable);
        }

        Page<Document> result = documentRepository.findAccessibleDocumentsPaginated(
                userId,
                deptId,
                roleCode,
                normalizedKeyword,
                pageable);
        log.debug("[DOC-ACCESS] Found {} accessible documents for user {}", result.getTotalElements(), userId);
        return result;
    }

    private final com.aiagent.service.DecisionNumberService decisionNumberService;
    private final DocumentDuplicateDetectionService documentDuplicateDetectionService;

    @Transactional
    public Document uploadDocument(String title, String content, java.util.List<Long> departmentIds,
                                 java.util.List<Long> projectIds, AccessLevel accessLevel,
                                 String decision, com.aiagent.model.DocumentClassification classification,
                                 String projectName, String description, boolean internalSourceFlag,
                                 MultipartFile file, User uploader) throws java.io.IOException {

        if (uploader == null || uploader.getRole() == null) {
            throw new SecurityException("Không có quyền tải lên tài liệu.");
        }

        if (!documentAccessService.canUpload(uploader)) {
            throw new SecurityException("Bạn không có quyền tải lên tài liệu.");
        }

        if (AccessLevel.DEPARTMENT.equals(accessLevel) && (departmentIds == null || departmentIds.isEmpty())) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một phòng ban cho mức truy cập DEPARTMENT.");
        }
        if (AccessLevel.PROJECT.equals(accessLevel) && (projectIds == null || projectIds.isEmpty())) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một dự án cho mức truy cập PROJECT.");
        }

        // --- Duplicate detection gate (Level 1/2/3). Runs entirely BEFORE any
        // Document row or physical file is persisted, so a rejected upload
        // never creates a row, never writes a file, never touches Qdrant.
        // See DocumentDuplicateDetectionService for the access-scoped
        // disclosure rule applied to the thrown exception. ---
        String fileExtension = null;
        String fileHash = null;
        String normalizedFileContent = null;
        String contentHash = null;
        List<org.springframework.ai.document.Document> preSplitChunks = null;

        boolean hasFile = file != null && !file.isEmpty();
        if (hasFile) {
            fileExtension = determineValidatedExtension(file);

            fileHash = documentDuplicateDetectionService.hashBytes(file.getInputStream());
            documentDuplicateDetectionService.checkFileDuplicate(fileHash, uploader);

            String extractedText = extractTextSafely(file);
            if (extractedText != null && !extractedText.isBlank()) {
                normalizedFileContent = com.aiagent.util.NormalizationUtils.normalize(extractedText);
                contentHash = documentDuplicateDetectionService.hashText(normalizedFileContent);
                documentDuplicateDetectionService.checkContentDuplicate(contentHash, uploader);

                TokenTextSplitter splitter = new TokenTextSplitter(800, 100, 5, 10000, true);
                preSplitChunks = splitter.apply(List.of(
                        new org.springframework.ai.document.Document(normalizedFileContent, Map.of())));
                documentDuplicateDetectionService.checkSemanticDuplicate(preSplitChunks, uploader);
            } else {
                log.info("[DUPLICATE-CHECK] File has no extractable text (scanned/unsupported) — content/semantic checks skipped for uploader {}.",
                        uploader.getEmail());
            }
        }

        Document doc = new Document();
        doc.setTitle(title);
        doc.setContent(normalizedFileContent != null ? normalizedFileContent : (content != null ? content : ""));
        doc.setAccessLevel(accessLevel != null ? accessLevel : AccessLevel.DEPARTMENT);
        doc.setUploadedBy(uploader);
        doc.setClassification(classification != null ? classification : com.aiagent.model.DocumentClassification.OTHER);
        doc.setDescription(description);
        doc.setInternalSourceFlag(internalSourceFlag);
        doc.setProjectName(projectName);
        doc.setFileHash(fileHash);
        doc.setContentHash(contentHash);

        if (com.aiagent.model.DocumentClassification.DECISION_DOCUMENT.equals(classification)) {
            doc.setDecisionNumber(decisionNumberService.generateNextDecisionNumber());
        } else {
            doc.setDecisionNumber(decision);
        }

        if (AccessLevel.DEPARTMENT.equals(accessLevel)) {
            doc.setProjects(new java.util.HashSet<>());
            doc.setProjectName(null);

            if (RoleConstants.ROLE_MANAGER.equals(uploader.getRole().getCode()) && uploader.getDepartment() != null) {
                log.info("[SECURITY-ENFORCE] Restricting MANAGER {} to upload only to department: {}",
                        uploader.getEmail(), uploader.getDepartment().getCode());
                departmentIds = java.util.List.of(uploader.getDepartment().getId());
            }

            if (departmentIds != null && !departmentIds.isEmpty()) {
                List<com.aiagent.model.Department> resolvedDepartments = departmentRepository.findAllById(departmentIds);
                if (resolvedDepartments.stream().anyMatch(dept -> "ALL".equals(dept.getCode()))) {
                    throw new IllegalArgumentException("Không được chọn phòng ban 'Tất cả' cho tài liệu phạm vi Phòng ban.");
                }

                Long primaryDepartmentId = departmentIds.get(0);
                doc.setDepartments(new java.util.HashSet<>(resolvedDepartments));
                doc.setDepartmentName(resolvedDepartments.stream()
                        .filter(dept -> dept.getId().equals(primaryDepartmentId))
                        .findFirst()
                        .map(com.aiagent.model.Department::getName).orElse("UNKNOWN"));
            }
        } else if (AccessLevel.PROJECT.equals(accessLevel)) {
            doc.setDepartments(new java.util.HashSet<>());
            doc.setDepartmentName(null);
            if (projectIds != null && !projectIds.isEmpty()) {
                doc.setProjects(new java.util.HashSet<>(projectRepository.findAllById(projectIds)));
                if (doc.getProjectName() == null || doc.getProjectName().isBlank()) {
                    doc.setProjectName(projectRepository.findById(projectIds.get(0)).map(com.aiagent.model.Project::getName).orElse("N/A"));
                }
            }
        } else if (AccessLevel.PRIVATE.equals(accessLevel)) {
            doc.setDepartments(new java.util.HashSet<>());
            doc.setProjects(new java.util.HashSet<>());
            doc.setDepartmentName(null);
            doc.setProjectName(null);
        } else if (AccessLevel.PUBLIC.equals(accessLevel)) {
        }

        Document savedDoc;
        try {
            savedDoc = documentRepository.save(doc);
        } catch (DataIntegrityViolationException e) {
            // Losing side of a concurrent duplicate upload: the application-level
            // checks above passed, but another request committed the same
            // file/content hash first (fileHash/contentHash carry a UNIQUE DB
            // constraint — see V5 migration — which is the real race-condition
            // guard, the earlier checks are only a fast-path). Re-run the exact
            // checks so the loser gets the same structured duplicate response
            // instead of a raw 500.
            log.warn("[DUPLICATE-RACE] Unique constraint violated on save for uploader {} — re-checking hashes.", uploader.getEmail());
            documentDuplicateDetectionService.checkFileDuplicate(fileHash, uploader);
            documentDuplicateDetectionService.checkContentDuplicate(contentHash, uploader);
            // Neither hash matched on re-check: the violation wasn't our
            // duplicate guard — surface the original error rather than
            // misreporting an unrelated constraint failure as a duplicate.
            throw e;
        }

        if (hasFile) {
            String savedPath = writeFileToDisk(file, fileExtension);
            savedDoc.setFilePath(savedPath);
            savedDoc.setFileType(getExtension(file.getOriginalFilename()));

            documentRepository.save(savedDoc);

            java.util.List<Long> resolvedDeptIds = savedDoc.getDepartments().stream()
                    .map(com.aiagent.model.Department::getId)
                    .collect(java.util.stream.Collectors.toList());
            java.util.List<Long> resolvedProjIds = savedDoc.getProjects().stream()
                    .map(com.aiagent.model.Project::getId)
                    .collect(java.util.stream.Collectors.toList());

            String uploaderName = uploader.getUsername();
            String uploaderRole = uploader.getRole() != null ? uploader.getRole().getName() : "STAFF";
            String departmentNames = savedDoc.getDepartmentName();
            if (departmentNames == null || departmentNames.isEmpty()) {
                departmentNames = uploader.getDepartment() != null ? uploader.getDepartment().getName() : "UNKNOWN";
            }

            log.info("[INGESTION-PREP] docId={}, title={}, accessLevel={}, deptIds={}, projIds={}",
                    savedDoc.getId(), savedDoc.getTitle(), savedDoc.getAccessLevel(), resolvedDeptIds, resolvedProjIds);

            try {
                if (preSplitChunks != null) {
                    // Text was already extracted + chunked above for the
                    // duplicate check — reuse it instead of parsing the file
                    // with Tika a second time.
                    documentIngestionService.ingestPreExtracted(
                            preSplitChunks, savedDoc.getId(), savedDoc.getDocumentUuid(), savedDoc.getTitle(), savedDoc.getFileType(),
                            uploader.getId(), uploaderName, uploaderRole, departmentNames, savedDoc.getDecisionNumber(),
                            savedDoc.getClassification().name(), savedDoc.getProjectName(), savedDoc.getDescription(),
                            savedDoc.isInternalSourceFlag(),
                            savedDoc.getAccessLevel().name(),
                            resolvedDeptIds, resolvedProjIds, savedDoc.getCreatedAt(), savedDoc.getVersion());
                } else {
                    // No extractable text was found during the duplicate check
                    // (e.g. scanned PDF) — fall back to the from-disk pipeline,
                    // which handles that case the same way it always has.
                    documentIngestionService.ingestDocument(
                            savedPath, savedDoc.getId(), savedDoc.getDocumentUuid(), savedDoc.getTitle(), savedDoc.getFileType(),
                            uploader.getId(), uploaderName, uploaderRole, departmentNames, savedDoc.getDecisionNumber(),
                            savedDoc.getClassification().name(), savedDoc.getProjectName(), savedDoc.getDescription(),
                            savedDoc.isInternalSourceFlag(),
                            savedDoc.getAccessLevel().name(),
                            resolvedDeptIds, resolvedProjIds, savedDoc.getCreatedAt(), savedDoc.getVersion());
                }
            } catch (Exception e) {
                log.error("Ingestion vào Qdrant thất bại cho document {}: {}", savedDoc.getId(), e.getMessage());
            }

            // Pipeline Viewer — song song, độc lập với ingestion RAG ở trên.
            // Không đụng filePath (vẫn trỏ file gốc cho Tika/RAG).
            try {
                if ("PDF".equalsIgnoreCase(savedDoc.getFileType())) {
                    savedDoc.setViewerFilePath(savedPath);
                    savedDoc.setViewerStatus(com.aiagent.model.ViewerStatus.READY);
                    documentRepository.save(savedDoc);
                } else if (VIEWER_CONVERTIBLE_EXTENSIONS.contains(savedDoc.getFileType().toUpperCase())) {
                    savedDoc.setViewerStatus(com.aiagent.model.ViewerStatus.PROCESSING);
                    documentRepository.save(savedDoc);
                    documentViewerConversionService.convertToViewerPdfAsync(
                            savedDoc.getId(), savedPath, savedDoc.getDocumentUuid(), savedDoc.getFileType());
                } else {
                    savedDoc.setViewerStatus(com.aiagent.model.ViewerStatus.UNSUPPORTED);
                    documentRepository.save(savedDoc);
                }
            } catch (Exception e) {
                log.error("Viewer pipeline thất bại cho document {}: {}", savedDoc.getId(), e.getMessage());
            }
        }

        return savedDoc;
    }

    public Document getDocument(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Tài liệu không tồn tại"));
    }

    public boolean canAccess(User user, Document doc) {
        return documentAccessService.canAccessDocument(user, doc);
    }

    /**
     * Best-effort text extraction used ONLY for the pre-persist duplicate
     * check. Never throws — an unreadable/scanned/corrupted file is a normal,
     * expected case (WORKING_RULES: must not crash on unsupported content),
     * it just means content/semantic duplicate checks are skipped and the
     * file falls back to the from-disk ingestion pipeline after acceptance.
     */
    private String extractTextSafely(MultipartFile file) {
        try {
            Resource resource = new InputStreamResource(file.getInputStream());
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<org.springframework.ai.document.Document> documents = reader.read();
            if (documents.isEmpty()) {
                return null;
            }
            return documents.get(0).getContent();
        } catch (Exception e) {
            log.warn("[DUPLICATE-CHECK] Tika extraction failed during duplicate pre-check (file may be scanned/corrupted/unsupported): {}", e.getMessage());
            return null;
        }
    }

    /**
     * Filename/extension/size/magic-byte MIME validation (SEC-004) — does NOT
     * write anything to disk. Split out of the old saveFile() so it can run
     * before the duplicate-detection hashing, without prematurely persisting
     * a file for a request that might still be rejected as a duplicate.
     */
    private String determineValidatedExtension(MultipartFile file) throws IOException {
        validateOriginalFilename(file.getOriginalFilename());

        String extension = getExtension(file.getOriginalFilename()).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Loại file không được hỗ trợ. Chỉ chấp nhận: " + ALLOWED_EXTENSIONS);
        }

        if (file.getSize() <= 0) {
            throw new IllegalArgumentException("File rỗng, vui lòng chọn file khác.");
        }
        long maxBytes = maxUploadSizeMb * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("Kích thước file vượt quá giới hạn cho phép (" + maxUploadSizeMb + "MB).");
        }

        String detectedMimeType = detectContentType(file);
        String expectedMimeType = ALLOWED_MIME_TYPE_BY_EXTENSION.get(extension);
        if (!expectedMimeType.equals(detectedMimeType)) {
            log.warn("[SEC-004] Rejected upload: extension='{}' expected content type='{}' but detected='{}' (declared Content-Type='{}')",
                    extension, expectedMimeType, detectedMimeType, file.getContentType());
            throw new IllegalArgumentException("Nội dung file không khớp với định dạng đã khai báo (." + extension + ").");
        }

        return extension;
    }

    /**
     * Physically writes an already-validated file to disk under a
     * server-generated UUID name. Only ever called after every duplicate
     * check has passed.
     */
    private String writeFileToDisk(MultipartFile file, String extension) throws IOException {
        // Filename is fully server-generated (UUID + validated extension) so the
        // client-supplied original filename can never influence the physical path.
        Path baseDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(baseDir);

        String serverFileName = UUID.randomUUID() + "." + extension;
        Path target = baseDir.resolve(serverFileName).normalize();
        if (!baseDir.equals(target.getParent())) {
            throw new SecurityException("Đường dẫn file không hợp lệ.");
        }

        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        return target.toString();
    }

    private void validateOriginalFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("Tên file không hợp lệ.");
        }
        String normalized = originalFilename.replace('\\', '/');
        if (normalized.contains("/") || normalized.contains("..")) {
            throw new IllegalArgumentException("Tên file chứa ký tự không hợp lệ.");
        }
    }

    private String detectContentType(MultipartFile file) throws IOException {
        org.apache.tika.Tika tika = new org.apache.tika.Tika();
        try (java.io.InputStream in = file.getInputStream()) {
            return tika.detect(in);
        }
    }

    private String getExtension(String filename) {
        if (filename == null)
            return "";
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(idx + 1).toUpperCase() : "";
    }

    @Transactional
    public void deleteDocument(Long id, User requester) {
        Document doc = getDocument(id);

        boolean canDelete = false;
        if (requester != null && requester.getRole() != null) {
            String roleCode = requester.getRole().getCode();
            if (RoleConstants.isHighLevel(roleCode)) {
                canDelete = true;
            } else if (RoleConstants.ROLE_MANAGER.equals(roleCode)
                    && doc.getUploadedBy() != null && doc.getUploadedBy().getId().equals(requester.getId())) {
                // Chặn tường minh theo role thay vì chỉ dựa vào bất biến ngầm
                // "EMPLOYEE không thể là uploader" — EMPLOYEE không được xóa
                // document dù vô tình là owner.
                canDelete = true;
            }
        }

        if (!canDelete) {
            throw new SecurityException("Bạn không có quyền xoá tài liệu này.");
        }

        try {
            documentIngestionService.deleteFromVectorStore(id);
        } catch (Exception e) {
            log.error("Failed to remove document {} from vector store: {}", id, e.getMessage());
        }

        documentRepository.delete(doc);

        if (doc.getFilePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(doc.getFilePath()));
            } catch (IOException e) {
                log.warn("Không thể xóa file vật lý cho tài liệu {}: {}", id, e.getMessage());
            }
        }
    }
}
