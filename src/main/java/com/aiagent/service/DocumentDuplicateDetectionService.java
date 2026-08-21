package com.aiagent.service;

import com.aiagent.exception.DocumentDuplicateException;
import com.aiagent.model.Document;
import com.aiagent.model.DocumentDuplicateType;
import com.aiagent.model.DocumentStatus;
import com.aiagent.model.User;
import com.aiagent.rag.SemanticDuplicateDetectionService;
import com.aiagent.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the 3-tier duplicate check that gates document upload (see
 * DocumentService#uploadDocument). Every check runs BEFORE the Document row
 * is persisted and BEFORE the file is written to disk, so a rejected upload
 * never creates a row, never writes a file, and never triggers Qdrant
 * ingestion.
 *
 * Disclosure rule (WORKING_RULES §17 / §4): the upload is always rejected on
 * a match, but the existing document's id/title/location (department or
 * project name, uploader, upload date) are only included in the thrown
 * exception when the uploader can actually access that document
 * (DocumentAccessService#canAccessDocument). Otherwise a fully generic
 * message is used — the caller learns "this content already exists" but not
 * which department/project document it belongs to.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentDuplicateDetectionService {

    private static final String GENERIC_MESSAGE = "Tài liệu này đã tồn tại trên hệ thống và không thể tải lên.";
    private static final DateTimeFormatter UPLOAD_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final DocumentRepository documentRepository;
    private final DocumentAccessService documentAccessService;
    private final SemanticDuplicateDetectionService semanticDuplicateDetectionService;

    @Value("${app.document.duplicate.enabled:true}")
    private boolean duplicateDetectionEnabled;

    public String hashBytes(InputStream inputStream) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return toHex(digest.digest());
    }

    public String hashText(String normalizedText) {
        MessageDigest digest = sha256Digest();
        return toHex(digest.digest(normalizedText.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Only matches against APPROVED documents: neither PENDING_APPROVAL nor
     * REJECTED blocks a (re-)submission -- a Manager may freely upload the
     * same file/content again while an earlier submission is still awaiting
     * a decision (there is no "edit while pending" flow; re-upload is the
     * only way to change it), and a rejection doesn't permanently reserve
     * the file/content hash either. Once a document is APPROVED, duplicates
     * against it are blocked as before.
     */
    public void checkFileDuplicate(String fileHash, User uploader) {
        if (!duplicateDetectionEnabled || fileHash == null) {
            return;
        }
        documentRepository.findByFileHashAndIsDeletedFalseAndStatus(fileHash, DocumentStatus.APPROVED)
                .ifPresent(existing -> {
                    throw buildException(DocumentDuplicateType.DUPLICATE_FILE, existing, uploader);
                });
    }

    public void checkContentDuplicate(String contentHash, User uploader) {
        if (!duplicateDetectionEnabled || contentHash == null) {
            return;
        }
        documentRepository.findByContentHashAndIsDeletedFalseAndStatus(contentHash, DocumentStatus.APPROVED)
                .ifPresent(existing -> {
                    throw buildException(DocumentDuplicateType.DUPLICATE_CONTENT, existing, uploader);
                });
    }

    public void checkSemanticDuplicate(List<org.springframework.ai.document.Document> chunks, User uploader) {
        if (!duplicateDetectionEnabled || chunks == null || chunks.isEmpty()) {
            return;
        }
        Optional<SemanticDuplicateDetectionService.SemanticMatch> match = semanticDuplicateDetectionService
                .findDuplicate(chunks, uploader);
        if (match.isPresent()) {
            throw buildException(DocumentDuplicateType.DUPLICATE_SEMANTIC, match.get().document(), uploader);
        }
    }

    private DocumentDuplicateException buildException(DocumentDuplicateType type, Document existing, User uploader) {
        boolean canSeeExisting = documentAccessService.canAccessDocument(uploader, existing);
        Long id = canSeeExisting ? existing.getId() : null;
        String name = canSeeExisting ? existing.getTitle() : null;
        String message = canSeeExisting ? buildLocatedMessage(existing) : GENERIC_MESSAGE;

        log.info("[DUPLICATE-DETECTED] type={}, existingDocId={}, requesterCanAccess={}",
                type, existing.getId(), canSeeExisting);

        return new DocumentDuplicateException(type, id, name, message);
    }

    /**
     * Chỉ được gọi khi uploader đã canAccessDocument(existing) == true --
     * KHÔNG được lộ phòng ban/dự án của một tài liệu mà uploader không có
     * quyền xem, kể cả khi nội dung trùng khớp (WORKING_RULES §4/§7: tránh
     * leak scope/sự tồn tại của tài liệu ngoài phạm vi qua thông báo lỗi).
     */
    private String buildLocatedMessage(Document existing) {
        String location = switch (existing.getAccessLevel()) {
            case PUBLIC -> "tài liệu công khai (PUBLIC)";
            case DEPARTMENT -> "phòng ban " +
                    (existing.getDepartmentName() != null ? existing.getDepartmentName() : "không xác định");
            case PROJECT -> "dự án " +
                    (existing.getProjectName() != null ? existing.getProjectName() : "không xác định");
        };
        String uploadDate = existing.getCreatedAt() != null
                ? existing.getCreatedAt().format(UPLOAD_DATE_FORMAT)
                : "không rõ ngày";
        return String.format(
                "Tài liệu này đã tồn tại trên hệ thống: \"%s\" (thuộc %s, tải lên bởi %s ngày %s).",
                existing.getTitle(), location, existing.getUploaderName(), uploadDate);
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm; this can only happen with a
            // broken JVM install, which is unrecoverable at this call site.
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
