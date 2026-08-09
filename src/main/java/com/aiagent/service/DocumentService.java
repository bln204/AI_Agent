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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt");

    private static final Map<String, String> ALLOWED_MIME_TYPE_BY_EXTENSION = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "txt", "text/plain"
    );

    private final DocumentRepository documentRepository;
    private final DocumentIngestionService documentIngestionService;
    private final DepartmentRepository departmentRepository;
    private final ProjectRepository projectRepository;
    private final DocumentAccessService documentAccessService;

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

        Document doc = new Document();
        doc.setTitle(title);
        doc.setContent(content != null ? content : "");
        doc.setAccessLevel(accessLevel != null ? accessLevel : AccessLevel.DEPARTMENT);
        doc.setUploadedBy(uploader);
        doc.setClassification(classification != null ? classification : com.aiagent.model.DocumentClassification.OTHER);
        doc.setDescription(description);
        doc.setInternalSourceFlag(internalSourceFlag);
        doc.setProjectName(projectName);
        
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
                doc.setDepartments(new java.util.HashSet<>(departmentRepository.findAllById(departmentIds)));
                doc.setDepartmentName(departmentRepository.findById(departmentIds.get(0)).map(com.aiagent.model.Department::getName).orElse("UNKNOWN"));
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

        Document savedDoc = documentRepository.save(doc);

        if (file != null && !file.isEmpty()) {
            String savedPath = saveFile(file);
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
                documentIngestionService.ingestDocument(
                        savedPath, savedDoc.getId(), savedDoc.getDocumentUuid(), savedDoc.getTitle(), savedDoc.getFileType(),
                        uploader.getId(), uploaderName, uploaderRole, departmentNames, savedDoc.getDecisionNumber(),
                        savedDoc.getClassification().name(), savedDoc.getProjectName(), savedDoc.getDescription(), 
                        savedDoc.isInternalSourceFlag(),
                        savedDoc.getAccessLevel().name(),
                        resolvedDeptIds, resolvedProjIds, savedDoc.getCreatedAt(), savedDoc.getVersion());
            } catch (Exception e) {
                log.error("Ingestion vào Qdrant thất bại cho document {}: {}", savedDoc.getId(), e.getMessage());
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

    private String saveFile(MultipartFile file) throws IOException {
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
