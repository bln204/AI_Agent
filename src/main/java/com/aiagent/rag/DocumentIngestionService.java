package com.aiagent.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.scheduling.annotation.Async;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;

    /**
     * Ingest document vào Qdrant (Chạy ngầm để không làm block Web Request).
     * 
     * CRITICAL FIX: All entity metadata (departmentIds, projectIds, accessLevel, title)
     * must be resolved BEFORE calling this @Async method. Lazy-loaded Hibernate proxies
     * are NOT accessible in a separate thread after the original session closes.
     */
    @Async
    public void ingestDocument(String filePath, Long documentId, String title, String fileType,
                                Long userId, String accessLevel,
                                List<Long> departmentIds, List<Long> projectIds) {
        ingestDocumentSync(filePath, documentId, title, fileType, userId, accessLevel, departmentIds, projectIds);
    }

    /**
     * Synchronous version of ingestDocument for batch processing.
     */
    public void ingestDocumentSync(String filePath, Long documentId, String title, String fileType,
                                    Long userId, String accessLevel,
                                    List<Long> departmentIds, List<Long> projectIds) {
        if (filePath == null) {
            log.error("File path is missing for document ID: {}", documentId);
            return;
        }

        java.io.File actualFile = new java.io.File(filePath);
        if (!actualFile.exists()) {
            log.error("File not found on disk at: {} for document ID: {}", filePath, documentId);
            return;
        }

        Resource resource = new FileSystemResource(actualFile);

        try {
            long startTime = System.currentTimeMillis();
            log.info("============== DOCUMENT INGESTION PIPELINE START ==============");
            log.info("File Path: {} | User ID: {} | Document ID: {}", filePath, userId, documentId);
            log.info("Metadata: accessLevel={}, departmentIds={}, projectIds={}", accessLevel, departmentIds, projectIds);

            // Đọc file bằng Tika
            log.info("[1/3] Đang dùng Tika để Extract nội dung tử file gốc...");
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> documents = reader.read();

            if (documents.isEmpty() || documents.get(0).getContent().trim().isEmpty()) {
                log.warn("❌ CẢNH BÁO: File PDF/Doc không có TEXT (có thể là file scan hoặc rỗng): {}", actualFile.getName());
                return;
            }
            log.info("=> Tika Extract thành công! Chiều dài tổng cộng: ~{} ký tự", documents.get(0).getContent().length());

            // Chunking Strategy (Layer 7): Optimized for both Specific and Abstract queries
            log.info("[2/3] Thực hiện cắt Chunk bằng TokenTextSplitter (800 tokens, 100 overlap)...");
            // 800 tokens is roughly ~3000 chars, good for capturing enough context for abstract questions
            TokenTextSplitter splitter = new TokenTextSplitter(800, 100, 5, 10000, true);
            List<Document> splitDocuments = splitter.apply(documents);

            log.info("=> File được cắt thành {} chunks.", splitDocuments.size());

            // Enrich metadata — using pre-resolved primitives (NO lazy proxy access)
            final String resolvedTitle = (title != null) ? title : actualFile.getName();
            final String resolvedAccessLevel = (accessLevel != null) ? accessLevel : "PUBLIC";
            final String resolvedFileType = (fileType != null) ? fileType.toLowerCase() : "unknown";
            final List<Long> resolvedDeptIds = (departmentIds != null) ? departmentIds : List.of();
            final List<Long> resolvedProjIds = (projectIds != null) ? projectIds : List.of();

            // 1. Fail-Safe Validation: Check accessLevel requirements before mapping chunks
            if ("PROJECT".equalsIgnoreCase(resolvedAccessLevel) && resolvedProjIds.isEmpty()) {
                log.error("[RAG-INGEST-ABORT] Doc ID {} is marked as PROJECT but has NO project IDs. Aborting ingestion for security.", documentId);
                return;
            }
            if ("DEPARTMENT".equalsIgnoreCase(resolvedAccessLevel) && resolvedDeptIds.isEmpty()) {
                log.error("[RAG-INGEST-ABORT] Doc ID {} is marked as DEPARTMENT but has NO department IDs. Aborting ingestion.", documentId);
                return;
            }

            List<Document> finalDocuments = splitDocuments.stream().map(doc -> {
                Map<String, Object> metadata = new HashMap<>(doc.getMetadata());

                metadata.put("document_id", String.valueOf(documentId));
                metadata.put("document_name", resolvedTitle);
                metadata.put("access_level", resolvedAccessLevel);
                metadata.put("uploader_id", String.valueOf(userId));
                metadata.put("file_type", resolvedFileType);
                metadata.put("ingested_at", String.valueOf(System.currentTimeMillis()));

                // Standardized Keys (Layer 7 Strategy)
                if (!resolvedDeptIds.isEmpty()) {
                    metadata.put("department_id", String.valueOf(resolvedDeptIds.get(0)));
                }

                if (!resolvedProjIds.isEmpty()) {
                    metadata.put("project_id", String.valueOf(resolvedProjIds.get(0)));
                }

                return new Document(doc.getContent(), metadata);
            }).collect(Collectors.toList());

            log.info("[2.5/3] Kiểm tra HuggingFace Local Model vector size...");
            var sampleEmbedded = embeddingModel.embed(splitDocuments.get(0).getContent());
            int vectorSize = (sampleEmbedded != null) ? sampleEmbedded.size() : 0;
            log.info("=> Embedding sample thành công! Vector size: {}", vectorSize);

            log.info("[3/3] Tiến hành Upsert {} chunks vào Qdrant (Mã hóa HuggingFace Local sẽ tự động chạy ngầm)...", finalDocuments.size());
            
            long qdrantStart = System.currentTimeMillis();
            try {
                vectorStore.add(finalDocuments);
                long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
                long qdrantElapsed = (System.currentTimeMillis() - qdrantStart);
                
                log.info("✅ THÀNH CÔNG: Upsert {} vectors vào Qdrant mất {}ms.", finalDocuments.size(), qdrantElapsed);
                log.info("============== DOCUMENT INGESTION PIPELINE SUCCESS ({}s) ==============", elapsedSeconds);
            } catch (io.grpc.StatusRuntimeException grpcException) {
                log.error("❌ LỖI MẠNG QDRANT (gRPC port 6334) khi Upsert document ID {}:", documentId, grpcException);
                throw new RuntimeException("Qdrant gRPC connection failed: " + grpcException.getMessage(), grpcException);
            }
        } catch (Exception e) {
            log.error("❌ QUÁ TRÌNH INGESTION BỊ CHẶN LẠI HOẶC LỖI CHO DOC ID {}:", documentId, e);
            throw new RuntimeException("Ingestion failed: " + e.getMessage(), e);
        }
    }
}