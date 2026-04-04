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
     * Ingest document vào Qdrant (Chạy ngầm để không làm block Web Request)
     */
    @Async
    public void ingestDocument(com.aiagent.model.Document savedDoc, Long userId) throws java.io.IOException {
        String filePath = savedDoc.getFilePath();
        if (filePath == null) {
            throw new IllegalArgumentException("File path is missing from the saved document");
        }

        java.io.File actualFile = new java.io.File(filePath);
        if (!actualFile.exists()) {
            throw new IllegalArgumentException("File not found on disk at: " + filePath);
        }

        Resource resource = new FileSystemResource(actualFile);

        try {
            long startTime = System.currentTimeMillis();
            log.info("============== DOCUMENT INGESTION PIPELINE START ==============");
            log.info("File Path: {} | User ID: {} | Document ID: {}", filePath, userId, savedDoc.getId());

            // Đọc file bằng Tika
            log.info("[1/3] Đang dùng Tika để Extract nội dung tử file gốc...");
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> documents = reader.read();

            if (documents.isEmpty() || documents.get(0).getContent().trim().isEmpty()) {
                log.warn("❌ CẢNH BÁO: File PDF/Doc không có TEXT (có thể là file scan hoặc rỗng): {}", actualFile.getName());
                return;
            }
            log.info("=> Tika Extract thành công! Chiều dài tổng cộng: ~{} ký tự", documents.get(0).getContent().length());

            // Chunking
            log.info("[2/3] Thực hiện cắt Chunk bằng TokenTextSplitter...");
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> splitDocuments = splitter.apply(documents);

            log.info("=> File được cắt thành {} chunks.", splitDocuments.size());

            // Thêm metadata
            // Enrich metadata - Chỉ dùng kiểu String để tương thích với Qdrant
            List<Document> finalDocuments = splitDocuments.stream().map(doc -> {
                Map<String, Object> metadata = new HashMap<>(doc.getMetadata()); // copy để an toàn

                metadata.put("user_id", userId.toString());
                metadata.put("document_id", String.valueOf(savedDoc.getId()));
                metadata.put("document_name", savedDoc.getTitle() != null ? savedDoc.getTitle() : actualFile.getName());
                metadata.put("source", actualFile.getName());
                metadata.put("ingested_at", String.valueOf(System.currentTimeMillis()));
                metadata.put("file_type", savedDoc.getFileType() != null ? savedDoc.getFileType().toLowerCase() : "unknown");
                
                // Mở rộng metadata cho RAG search query filtering
                metadata.put("access_level", savedDoc.getAccessLevel() != null ? savedDoc.getAccessLevel().name() : "PUBLIC");
                metadata.put("uploader_id", String.valueOf(userId));
                
                List<Long> departmentIds = savedDoc.getDepartments().stream()
                        .map(com.aiagent.model.Department::getId)
                        .collect(Collectors.toList());
                metadata.put("department_ids", departmentIds);

                List<Long> projectIds = savedDoc.getProjects().stream()
                        .map(com.aiagent.model.Project::getId)
                        .collect(Collectors.toList());
                metadata.put("project_ids", projectIds);

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
                log.error("❌ LỖI MẠNG QDRANT (gRPC port 6334) khi Upsert document ID {}:", savedDoc.getId(), grpcException);
                throw new RuntimeException("Qdrant gRPC connection failed: " + grpcException.getMessage(), grpcException);
            }
        } catch (Exception e) {
            log.error("❌ QUÁ TRÌNH INGESTION BỊ CHẶN LẠI HOẶC LỖI CHO DOC ID {}:", savedDoc.getId(), e);
            throw new RuntimeException("Ingestion failed: " + e.getMessage(), e);
        }
    }
}