package com.aiagent.service;

import com.aiagent.model.ViewerStatus;
import com.aiagent.repository.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Pipeline Viewer — hoàn toàn tách biệt khỏi DocumentIngestionService/RAG.
 * Trách nhiệm duy nhất: tạo bản PDF phục vụ Document Viewer cho các định
 * dạng cần convert (DOCX, TXT, XLSX). Không đọc/ghi filePath gốc, không động
 * tới Tika/TokenTextSplitter/EmbeddingModel/Qdrant.
 */
@Service
@Slf4j
public class DocumentViewerConversionService {

    // Phải khớp với DocumentService.VIEWER_CONVERTIBLE_EXTENSIONS — định dạng
    // LibreOffice headless convert được sang PDF (PDF gốc không cần convert).
    private static final Set<String> CONVERTIBLE_EXTENSIONS = Set.of("DOCX", "TXT", "XLSX");

    private final DocumentRepository documentRepository;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Value("${app.viewer.libreoffice-path:soffice}")
    private String libreOfficePath;

    @Value("${app.viewer.conversion-timeout-seconds:60}")
    private long conversionTimeoutSeconds;

    public DocumentViewerConversionService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Async
    public void convertToViewerPdfAsync(Long documentId, String originalFilePath, String documentUuid, String fileType) {
        if (fileType == null || !CONVERTIBLE_EXTENSIONS.contains(fileType.toUpperCase())) {
            // Viewer không áp dụng cho định dạng này — no-op, không gọi
            // LibreOffice, không đụng tới viewerFilePath.
            return;
        }

        File originalFile = new File(originalFilePath);
        if (!originalFile.exists()) {
            log.error("[VIEWER-CONVERT] File gốc không tồn tại tại: {} cho document ID: {}", originalFilePath, documentId);
            markFailed(documentId);
            return;
        }

        Path outDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        String convertedBaseName = stripExtension(originalFile.getName());
        Path libreOfficeOutput = outDir.resolve(convertedBaseName + ".pdf");
        Path targetPath = outDir.resolve(documentUuid + "-viewer.pdf");

        try {
            log.info("[VIEWER-CONVERT] Bắt đầu convert {} -> PDF cho document ID: {}", fileType, documentId);

            ProcessBuilder pb = new ProcessBuilder(
                    libreOfficePath, "--headless", "--convert-to", "pdf",
                    "--outdir", outDir.toString(), originalFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(conversionTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("[VIEWER-CONVERT] Timeout sau {}s khi convert document ID: {}", conversionTimeoutSeconds, documentId);
                markFailed(documentId);
                return;
            }

            if (process.exitValue() != 0 || !Files.exists(libreOfficeOutput) || Files.size(libreOfficeOutput) <= 0) {
                log.error("[VIEWER-CONVERT] LibreOffice convert thất bại (exitCode={}) cho document ID: {}",
                        process.exitValue(), documentId);
                markFailed(documentId);
                return;
            }

            Files.move(libreOfficeOutput, targetPath, StandardCopyOption.REPLACE_EXISTING);

            documentRepository.findById(documentId).ifPresentOrElse(doc -> {
                doc.setViewerFilePath(targetPath.toString());
                doc.setViewerStatus(ViewerStatus.READY);
                documentRepository.save(doc);
                log.info("[VIEWER-CONVERT] Thành công cho document ID: {}", documentId);
            }, () -> log.warn("[VIEWER-CONVERT] Document ID {} không còn tồn tại trong DB, bỏ qua cập nhật viewerFilePath.", documentId));

        } catch (Exception e) {
            log.error("[VIEWER-CONVERT] Lỗi khi convert document ID {}: {}", documentId, e.getMessage());
            markFailed(documentId);
        }
    }

    private void markFailed(Long documentId) {
        documentRepository.findById(documentId).ifPresent(doc -> {
            doc.setViewerStatus(ViewerStatus.FAILED);
            documentRepository.save(doc);
        });
    }

    private String stripExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(0, idx) : filename;
    }
}
