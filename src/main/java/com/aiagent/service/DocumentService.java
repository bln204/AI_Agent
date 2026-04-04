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
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentIngestionService documentIngestionService;
    private final DepartmentRepository departmentRepository;
    private final ProjectRepository projectRepository;
    private final AccessPolicyService accessPolicyService;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    public Page<Document> getAccessibleDocumentsPaginated(User user, String keyword, Pageable pageable) {
        String normalizedKeyword = null;
        if (keyword != null && !keyword.trim().isEmpty()) {
            String temp = java.text.Normalizer.normalize(keyword.trim(), java.text.Normalizer.Form.NFD);
            normalizedKeyword = temp.replaceAll("\\p{M}", "").toLowerCase();
        }

        if (user == null) {
            return documentRepository.findPublicDocuments(normalizedKeyword, pageable);
        }

        String roleCode = user.getRole() != null ? user.getRole().getCode() : RoleConstants.ROLE_GUEST;
        Long userId = user.getId();
        Long deptId = user.getDepartment() != null ? user.getDepartment().getId() : -1L;

        if (RoleConstants.ROLE_DIRECTOR.equals(roleCode)) {
            return documentRepository.findAllAccessibleForDirector(normalizedKeyword, pageable);
        }

        return documentRepository.findAccessibleDocumentsPaginated(
                userId,
                deptId,
                roleCode,
                normalizedKeyword,
                pageable);
    }

    @Transactional
    public Document uploadDocument(String title, String content, java.util.List<Long> departmentIds, 
                                 java.util.List<Long> projectIds, AccessLevel accessLevel, 
                                 MultipartFile file, User uploader) throws java.io.IOException {

        if (uploader == null || uploader.getRole() == null) {
            throw new SecurityException("Không có quyền tải lên tài liệu.");
        }

        if (!accessPolicyService.canUpload(uploader)) {
            throw new SecurityException("Bạn không có quyền tải lên tài liệu.");
        }

        // Validation based on business rules
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

        // Set departments metadata (even if not strictly used for access, e.g. in PUBLIC/PRIVATE)
        if (departmentIds != null && !departmentIds.isEmpty()) {
            doc.setDepartments(new java.util.HashSet<>(departmentRepository.findAllById(departmentIds)));
        }

        // Set projects metadata
        if (projectIds != null && !projectIds.isEmpty()) {
            doc.setProjects(new java.util.HashSet<>(projectRepository.findAllById(projectIds)));
        }

        Document savedDoc = documentRepository.save(doc);

        if (file != null && !file.isEmpty()) {
            String savedPath = saveFile(file);
            savedDoc.setFilePath(savedPath);
            savedDoc.setFileType(getExtension(file.getOriginalFilename()));

            documentRepository.save(savedDoc);

            try {
                documentIngestionService.ingestDocument(savedDoc, uploader.getId());
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
        // Access logic is now centralized in AccessPolicyService
        // This is a placeholder for backward compatibility in controllers
        return true; 
    }

    private String saveFile(MultipartFile file) throws IOException {
        Path dir = Paths.get(uploadDir);
        Files.createDirectories(dir);
        String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path target = dir.resolve(fileName);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        return target.toString();
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
            } else if (doc.getUploadedBy() != null && doc.getUploadedBy().getId().equals(requester.getId())) {
                canDelete = true;
            }
        }
        
        if (!canDelete) {
            throw new SecurityException("Bạn không có quyền xoá tài liệu này.");
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
