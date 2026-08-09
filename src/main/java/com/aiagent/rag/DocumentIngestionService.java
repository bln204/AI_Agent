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
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import com.aiagent.repository.DocumentRepository;
import org.springframework.scheduling.annotation.Async;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final DocumentRepository documentRepository;

    @Async
    public void ingestDocument(String filePath, Long documentId, String documentUuid, String title, String fileType,
                                 Long userId, String userName, String uploaderRole, String department, String decisionNumber,
                                 String classification, String projectName, String description, boolean internalSourceFlag,
                                 String accessLevel,
                                 List<Long> departmentIds, List<Long> projectIds, java.time.LocalDateTime createdAt, Integer version) {
        ingestDocumentSync(filePath, documentId, documentUuid, title, fileType, userId, userName, uploaderRole, department, decisionNumber, classification, projectName, description, internalSourceFlag, accessLevel, departmentIds, projectIds, createdAt, version);
    }

    public void ingestDocumentSync(String filePath, Long documentId, String documentUuid, String title, String fileType,
                                    Long userId, String userName, String uploaderRole, String department, String decisionNumber,
                                    String classification, String projectName, String description, boolean internalSourceFlag,
                                    String accessLevel,
                                    List<Long> departmentIds, List<Long> projectIds, java.time.LocalDateTime createdAt, Integer version) {
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

            if (documents.isEmpty() || documents.get(0).getContent() == null || documents.get(0).getContent().trim().isEmpty()) {
                log.warn("❌ CẢNH BÁO: File PDF/Doc không có TEXT (có thể là file scan hoặc rỗng) cho Doc ID: {}. SKIP INDEXING.", documentId);
                return;
            }
            
            String rawContent = documents.get(0).getContent();
            String normalizedContent = com.aiagent.util.NormalizationUtils.normalize(rawContent);
            log.info("=> Tika Extract thành công! Chiều dài: ~{} ký tự (sau chuẩn hóa NFC)", normalizedContent.length());

            try {
                com.aiagent.model.Document dbDoc = documentRepository.findById(documentId).orElse(null);
                if (dbDoc != null) {
                    dbDoc.setContent(normalizedContent);
                    documentRepository.saveAndFlush(dbDoc);
                    log.info("✅ SUCCESS: Đã cập nhật nội dung văn bản vào Database cho Doc ID: {}", documentId);
                }
            } catch (Exception e) {
                log.error("⚠️ WARNING: Không thể cập nhật nội dung vào DB cho Doc ID: {}. Error: {}", documentId, e.getMessage());
            }

            org.springframework.ai.document.Document normalizedDoc = 
                new org.springframework.ai.document.Document(normalizedContent, documents.get(0).getMetadata());
            List<org.springframework.ai.document.Document> documentsToSplit = List.of(normalizedDoc);
            log.info("[2/3] Thực hiện cắt Chunk bằng TokenTextSplitter (800 tokens, 100 overlap)...");
            TokenTextSplitter splitter = new TokenTextSplitter(800, 100, 5, 10000, true);
            List<org.springframework.ai.document.Document> splitDocuments = splitter.apply(documentsToSplit);

            log.info("=> File được cắt thành {} chunks.", splitDocuments.size());

            final String resolvedTitle = (title != null) ? title : actualFile.getName();
            // Fail-closed: nếu accessLevel bị thiếu, mặc định PRIVATE (chỉ uploader +
            // DIRECTOR thấy được) thay vì PUBLIC (ai cũng thấy) — tránh lộ dữ liệu
            // ngoài ý muốn nếu caller mới quên truyền accessLevel.
            final String resolvedAccessLevel = (accessLevel != null) ? accessLevel : "PRIVATE";
            final String resolvedFileType = (fileType != null) ? fileType.toLowerCase() : "unknown";
            final List<Long> resolvedDeptIds = (departmentIds != null) ? departmentIds : List.of();
            final List<Long> resolvedProjIds = (projectIds != null) ? projectIds : List.of();
            
            final String formattedDate = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(createdAt != null ? createdAt : java.time.LocalDateTime.now());

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
                metadata.put("document_uuid", documentUuid != null ? documentUuid : "");
                metadata.put("version", String.valueOf(version != null ? version : 1));
                metadata.put("document_name", resolvedTitle);
                metadata.put("access_level", resolvedAccessLevel);
                metadata.put("uploader_id", String.valueOf(userId));
                metadata.put("uploader_role", uploaderRole != null ? uploaderRole : "UNKNOWN");
                metadata.put("file_type", resolvedFileType);
                metadata.put("ingested_at", String.valueOf(System.currentTimeMillis()));
                metadata.put("source", resolvedTitle);
                metadata.put("upload_date", formattedDate);
                metadata.put("user_name", userName != null ? userName : "UNKNOWN");
                metadata.put("department", department != null ? department : "UNKNOWN");
                metadata.put("decision_number", decisionNumber != null ? decisionNumber : "N/A");
                metadata.put("classification", classification != null ? classification : "OTHER");
                metadata.put("project_name", projectName != null ? projectName : "N/A");
                metadata.put("description", description != null ? description : "");
                metadata.put("internal_source_flag", String.valueOf(internalSourceFlag));

                // Lưu TOÀN BỘ department/project được gán cho document (không chỉ phần tử
                // đầu tiên) dưới dạng mảng — Qdrant match/in trên payload dạng mảng tự
                // kiểm tra "chứa phần tử" nên vẫn tương thích với filter hiện có.
                if ("DEPARTMENT".equalsIgnoreCase(resolvedAccessLevel) && !resolvedDeptIds.isEmpty()) {
                    metadata.put("department_ids", resolvedDeptIds.stream()
                            .map(String::valueOf).toArray(String[]::new));
                }

                if ("PROJECT".equalsIgnoreCase(resolvedAccessLevel) && !resolvedProjIds.isEmpty()) {
                    metadata.put("project_ids", resolvedProjIds.stream()
                            .map(String::valueOf).toArray(String[]::new));
                }

                String enrichedContent = String.format("DOCUMENT: %s\nPROJECT: %s\nDESCRIPTION: %s\n\n%s", 
                        resolvedTitle, 
                        projectName != null ? projectName : "N/A", 
                        description != null ? description : "", 
                        doc.getContent());

                log.info("[VERIFY-INGEST] Chunk for Doc {}: length={} chars", documentId, enrichedContent.length());

                return new Document(enrichedContent, metadata);
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

    /**
     * Removes all vectors associated with a document ID from Qdrant.
     */
    public void deleteFromVectorStore(Long documentId) {
        log.info("[VECTOR-CLEANUP] Removing vectors for docId: {}", documentId);
        try {
            SearchRequest searchRequest = SearchRequest.query("")
                    .withFilterExpression(new FilterExpressionBuilder().eq("document_id", String.valueOf(documentId)).build())
                    .withTopK(1000);
            
            List<org.springframework.ai.document.Document> chunks = vectorStore.similaritySearch(searchRequest);
            
            if (!chunks.isEmpty()) {
                List<String> chunkIds = chunks.stream()
                        .map(org.springframework.ai.document.Document::getId)
                        .collect(Collectors.toList());
                vectorStore.delete(chunkIds);
                log.info("[VECTOR-CLEANUP] Successfully purged {} chunks for docId: {}", chunkIds.size(), documentId);
            } else {
                log.info("[VECTOR-CLEANUP] No chunks found for docId: {} (already clean or never ingested)", documentId);
            }
        } catch (Exception e) {
            log.error("[VECTOR-CLEANUP] [FAILED] Could not purge vectors for docId: {}. Error: {}", documentId, e.getMessage());
        }
    }
}