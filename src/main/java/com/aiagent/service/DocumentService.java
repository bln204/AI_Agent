package com.aiagent.service;

import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.DocumentStatus;
import com.aiagent.model.User;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.ProjectRepository;
import com.aiagent.rag.DocumentIngestionService;
import com.aiagent.rag.xlsx.XlsxChunker;
import com.aiagent.rag.xlsx.XlsxDocumentReader;
import com.aiagent.rag.xlsx.XlsxSheetData;
import com.aiagent.rag.xlsx.XlsxStructuredTextBuilder;
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
    private final NotificationService notificationService;

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

    /**
     * Backs the "Công văn chờ duyệt" tab (status=PENDING_APPROVAL) and the
     * REJECTED history view. DIRECTOR sees every document in that status;
     * everyone else only sees their own (decision #2/#4).
     */
    public Page<Document> getDocumentsByStatus(User user, DocumentStatus status, Pageable pageable) {
        if (user == null || user.getRole() == null) {
            throw new SecurityException("Cần đăng nhập để xem danh sách này.");
        }
        String roleCode = user.getRole().getCode();
        return documentRepository.findByStatusVisibleTo(status, roleCode, user.getId(), pageable);
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

                if ("xlsx".equals(fileExtension)) {
                    // XLSX: chunk theo sheet/row (giữ header mỗi chunk) thay vì
                    // TokenTextSplitter chung, để không phá ngữ nghĩa bảng.
                    List<XlsxSheetData> sheets;
                    try (java.io.InputStream in = file.getInputStream()) {
                        sheets = new XlsxDocumentReader().readSheets(in);
                    }
                    log.info("[XLSX-INGEST] file={} sheets={}", file.getOriginalFilename(), sheets.size());
                    preSplitChunks = XlsxChunker.chunk(sheets);
                } else {
                    TokenTextSplitter splitter = new TokenTextSplitter(800, 100, 5, 10000, true);
                    preSplitChunks = splitter.apply(List.of(
                            new org.springframework.ai.document.Document(normalizedFileContent, Map.of())));
                }
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
        // Approval lifecycle: DIRECTOR (and ADMIN) uploads are visible/ingested
        // immediately as before; MANAGER uploads require DIRECTOR approval
        // first (business requirement) and must NOT be ingested into Qdrant
        // until approved -- see the `hasFile` ingestion block below, gated on
        // this same status.
        doc.setStatus(RoleConstants.isHighLevel(uploader.getRole().getCode())
                ? DocumentStatus.APPROVED
                : DocumentStatus.PENDING_APPROVAL);
        doc.setClassification(classification != null ? classification : com.aiagent.model.DocumentClassification.OTHER);
        doc.setDescription(description);
        doc.setInternalSourceFlag(internalSourceFlag);
        doc.setProjectName(projectName);
        // file_hash/content_hash carry a UNIQUE DB constraint (V5 migration) that
        // is not status-aware -- it blocks ANY row sharing the hash, regardless of
        // status. checkFileDuplicate/checkContentDuplicate above only ever match
        // against APPROVED documents (a Manager may resubmit the same file/content
        // while an earlier submission is PENDING_APPROVAL or was REJECTED), so the
        // hash columns must stay NULL for a PENDING_APPROVAL row -- MySQL's unique
        // index permits multiple NULLs -- otherwise a REJECTED document's leftover
        // hash would still collide at INSERT time. The hashes are computed and
        // persisted once the document actually becomes APPROVED: immediately here
        // for a DIRECTOR/ADMIN upload, or later in approveDocument() once a
        // MANAGER's submission is approved.
        if (doc.getStatus() == DocumentStatus.APPROVED) {
            doc.setFileHash(fileHash);
            doc.setContentHash(contentHash);

            // DIRECTOR/ADMIN uploads skip the PENDING_APPROVAL queue entirely, so
            // approveDocument() never runs for them -- record the uploader as the
            // approver right here instead, so approvedBy/approvedAt are always
            // populated for every APPROVED document (RAG source citation and the
            // document detail page both read these fields; leaving them null for
            // this path would silently omit "người duyệt" for self-approved docs).
            doc.setApprovedBy(uploader);
            doc.setApprovedAt(java.time.LocalDateTime.now());
        }

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

        if (savedDoc.getStatus() == DocumentStatus.PENDING_APPROVAL) {
            // Same transaction as the upload itself (decision: a notification
            // must never exist for an upload that ends up rolled back, and
            // vice versa a committed upload must not silently fail to notify).
            notificationService.notifyDirectorsOfPendingDocument(savedDoc);
        }

        if (hasFile) {
            String savedPath = writeFileToDisk(file, fileExtension);
            savedDoc.setFilePath(savedPath);
            savedDoc.setFileType(getExtension(file.getOriginalFilename()));

            documentRepository.save(savedDoc);

            if (savedDoc.getStatus() == DocumentStatus.APPROVED) {
                triggerIngestion(savedDoc, preSplitChunks, savedPath, uploader);
            } else {
                // PENDING_APPROVAL: approval gate (business requirement) —
                // must NOT be embedded/indexed into Qdrant until a DIRECTOR
                // approves. See approveDocument(), which calls
                // triggerIngestion() itself once status flips to APPROVED.
                log.info("[APPROVAL-GATE] docId={} status=PENDING_APPROVAL — ingestion deferred until DIRECTOR approves.",
                        savedDoc.getId());
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
     * Shared tail used by both the upload flow (when a DIRECTOR uploads, or
     * when approveDocument() flips a MANAGER's PENDING_APPROVAL document to
     * APPROVED) — builds the same metadata payload documentIngestionService
     * has always received and fires the (@Async) ingestion call. Failure is
     * logged only, never fails the caller's transaction, matching the
     * pre-existing behavior at the original upload call site.
     */
    private void triggerIngestion(Document savedDoc, List<org.springframework.ai.document.Document> preSplitChunks,
                                   String filePath, User uploader) {
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
        // Only reached once savedDoc.getStatus() == APPROVED (self-approved at
        // upload time for DIRECTOR/ADMIN, or flipped by approveDocument() for a
        // MANAGER submission), so approvedBy/approvedAt are always populated here.
        String approverName = savedDoc.getApprovedBy() != null ? savedDoc.getApprovedBy().getUsername() : null;
        // RAG source citation must never guess the approver's role/title from the
        // uploader's — read it from the actual approver account instead (approveDocument()
        // only ever lets a DIRECTOR approve a MANAGER's document; a DIRECTOR/ADMIN upload
        // is self-approved, so this is the uploader's own role in that case).
        String approverRole = (savedDoc.getApprovedBy() != null && savedDoc.getApprovedBy().getRole() != null)
                ? savedDoc.getApprovedBy().getRole().getName() : null;

        log.info("[INGESTION-PREP] docId={}, title={}, accessLevel={}, deptIds={}, projIds={}",
                savedDoc.getId(), savedDoc.getTitle(), savedDoc.getAccessLevel(), resolvedDeptIds, resolvedProjIds);

        try {
            if (preSplitChunks != null) {
                // Text was already extracted + chunked for the duplicate check
                // in the same request — reuse it instead of re-parsing the
                // file with Tika a second time.
                documentIngestionService.ingestPreExtracted(
                        preSplitChunks, savedDoc.getId(), savedDoc.getDocumentUuid(), savedDoc.getTitle(), savedDoc.getFileType(),
                        uploader.getId(), uploaderName, uploaderRole, departmentNames, savedDoc.getDecisionNumber(),
                        savedDoc.getClassification().name(), savedDoc.getProjectName(), savedDoc.getDescription(),
                        savedDoc.isInternalSourceFlag(),
                        savedDoc.getAccessLevel().name(),
                        resolvedDeptIds, resolvedProjIds, savedDoc.getCreatedAt(), savedDoc.getVersion(),
                        approverName, approverRole, savedDoc.getApprovedAt());
            } else {
                // No pre-split chunks available (approveDocument path, or the
                // original upload had no extractable text) — from-disk pipeline.
                documentIngestionService.ingestDocument(
                        filePath, savedDoc.getId(), savedDoc.getDocumentUuid(), savedDoc.getTitle(), savedDoc.getFileType(),
                        uploader.getId(), uploaderName, uploaderRole, departmentNames, savedDoc.getDecisionNumber(),
                        savedDoc.getClassification().name(), savedDoc.getProjectName(), savedDoc.getDescription(),
                        savedDoc.isInternalSourceFlag(),
                        savedDoc.getAccessLevel().name(),
                        resolvedDeptIds, resolvedProjIds, savedDoc.getCreatedAt(), savedDoc.getVersion(),
                        approverName, approverRole, savedDoc.getApprovedAt());
            }
        } catch (Exception e) {
            log.error("Ingestion vào Qdrant thất bại cho document {}: {}", savedDoc.getId(), e.getMessage());
        }
    }

    /**
     * DIRECTOR approves a MANAGER's PENDING_APPROVAL document: flips status to
     * APPROVED (only if it's still PENDING_APPROVAL — guards against
     * double-approval / concurrent approval races, decision #9) and, if a
     * file exists, triggers ingestion now that the document is allowed into
     * Qdrant/RAG.
     */
    @Transactional
    public Document approveDocument(Long id, User director) {
        requireDirector(director, "duyệt");

        int updated = documentRepository.approveIfPending(id, director, java.time.LocalDateTime.now());
        Document doc = rejectIfNoRowsUpdated(id, updated);

        if (doc.getFilePath() != null) {
            assignApprovedHashes(doc, director);
            triggerIngestion(doc, null, doc.getFilePath(), doc.getUploadedBy());
        }
        notificationService.notifyUploaderOfDecision(doc, true);
        return doc;
    }

    /**
     * A MANAGER upload keeps file_hash/content_hash NULL in the DB while
     * PENDING_APPROVAL/REJECTED (see uploadDocument) so the UNIQUE constraint on
     * those columns only ever guards APPROVED documents. Now that this document
     * is APPROVED, compute and persist the hashes so it correctly participates
     * in future duplicate checks, re-running checkFileDuplicate/checkContentDuplicate
     * first so a DIRECTOR can't approve two independently-submitted duplicates
     * into two APPROVED documents. A no-op for a DIRECTOR/ADMIN upload, which
     * already had its hashes set at upload time.
     *
     * File bytes are re-hashed from disk (writeFileToDisk does a byte-for-byte
     * copy, so this reproduces the exact upload-time hash) rather than
     * persisting the original hash somewhere pending approval. The content hash
     * reuses doc.getContent(), which already holds the same normalized text
     * that produced the upload-time content hash, avoiding a second Tika parse.
     */
    private void assignApprovedHashes(Document doc, User director) {
        if (doc.getFileHash() != null || doc.getContentHash() != null) {
            return;
        }

        String fileHash;
        try (java.io.InputStream in = Files.newInputStream(Paths.get(doc.getFilePath()))) {
            fileHash = documentDuplicateDetectionService.hashBytes(in);
        } catch (IOException e) {
            throw new IllegalStateException("Không thể đọc tệp tài liệu để duyệt.", e);
        }
        documentDuplicateDetectionService.checkFileDuplicate(fileHash, director);

        String contentHash = null;
        if (doc.getContent() != null && !doc.getContent().isBlank()) {
            contentHash = documentDuplicateDetectionService.hashText(doc.getContent());
            documentDuplicateDetectionService.checkContentDuplicate(contentHash, director);
        }

        doc.setFileHash(fileHash);
        doc.setContentHash(contentHash);
        try {
            documentRepository.save(doc);
        } catch (DataIntegrityViolationException e) {
            // Same race pattern as uploadDocument(): another document was approved
            // with the same hash between the check above and this save. Re-check so
            // the DIRECTOR gets the structured duplicate error instead of a raw 500.
            log.warn("[DUPLICATE-RACE] Unique constraint violated on approve for docId={} — re-checking hashes.", doc.getId());
            documentDuplicateDetectionService.checkFileDuplicate(fileHash, director);
            documentDuplicateDetectionService.checkContentDuplicate(contentHash, director);
            throw e;
        }
    }

    /**
     * DIRECTOR rejects a MANAGER's PENDING_APPROVAL document. The document
     * itself is kept (status=REJECTED, not deleted) so both the uploader and
     * DIRECTOR can still see it (decision #4); per decision #5, resubmission
     * happens by uploading a brand-new document, not by reusing this one.
     * Calls deleteFromVectorStore defensively even though a REJECTED document
     * should never have reached Qdrant in the first place (the approval gate
     * lives at upload time) — cheap insurance against that gate ever being
     * bypassed by a future bug.
     */
    @Transactional
    public Document rejectDocument(Long id, User director) {
        requireDirector(director, "từ chối");

        int updated = documentRepository.rejectIfPending(id, director, java.time.LocalDateTime.now());
        Document doc = rejectIfNoRowsUpdated(id, updated);

        try {
            documentIngestionService.deleteFromVectorStore(id);
        } catch (Exception e) {
            log.error("Failed to purge document {} from vector store after rejection: {}", id, e.getMessage());
        }
        notificationService.notifyUploaderOfDecision(doc, false);
        return doc;
    }

    private void requireDirector(User director, String action) {
        if (director == null || director.getRole() == null
                || !RoleConstants.ROLE_DIRECTOR.equals(director.getRole().getCode())) {
            throw new SecurityException("Chỉ Giám đốc mới có quyền " + action + " tài liệu.");
        }
    }

    /**
     * The conditional UPDATE in approveIfPending/rejectIfPending affects 0
     * rows either because the document doesn't exist, or (decision #9) because
     * another request already approved/rejected it first — distinguish the
     * two so the caller gets an accurate error instead of a generic failure.
     */
    private Document rejectIfNoRowsUpdated(Long id, int updatedRows) {
        Document current = getDocument(id);
        if (updatedRows == 0) {
            throw new IllegalStateException(
                    "Tài liệu đã được xử lý trước đó (trạng thái hiện tại: " + current.getStatus() + "). Vui lòng tải lại trang.");
        }
        return current;
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
            String extension = getExtension(file.getOriginalFilename()).toLowerCase();
            if ("xlsx".equals(extension)) {
                try (java.io.InputStream in = file.getInputStream()) {
                    List<XlsxSheetData> sheets = new XlsxDocumentReader().readSheets(in);
                    if (sheets.isEmpty()) {
                        return null;
                    }
                    return XlsxStructuredTextBuilder.buildFullText(sheets);
                }
            }

            Resource resource = new InputStreamResource(file.getInputStream());
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<org.springframework.ai.document.Document> documents = reader.read();
            if (documents.isEmpty()) {
                return null;
            }
            return documents.get(0).getContent();
        } catch (Exception e) {
            log.warn("[DUPLICATE-CHECK] Text extraction failed during duplicate pre-check (file may be scanned/corrupted/unsupported): {}", e.getMessage());
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
