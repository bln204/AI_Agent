package com.aiagent.rag;

import com.aiagent.model.ChatMessage;
import com.aiagent.model.MessageStatus;
import com.aiagent.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Backoff;

import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatService {

    private final ChatModel chatModel;
    private final RagRetrievalService retrievalService;
    private final QueryIntentClassifier queryIntentClassifier;

    @Value("${app.rag.max-context-chars:8000}")
    private int maxContextChars;

    private static final Set<String> GREETING_KEYWORDS = Set.of(
        "xin chào", "hi", "hello", "chào bạn", "chào buổi sáng", "chào buổi chiều", "chào buổi tối",
        "tạm biệt", "bye", "goodbye", "cảm ơn", "thank you", "thanks"
    );

    public ChatGenerationResult chat(Long sessionId, String question, User user, List<ChatMessage> history) {
        String normalizedQuestion = com.aiagent.util.NormalizationUtils.normalize(question);
        log.info("[AI-CHAT] [Stage=RETRIEVAL] session={}, user={}, question='{}'", 
                sessionId, user != null ? user.getEmail() : "Guest", question);

        // 1. Intent Detection
        if (isGreeting(normalizedQuestion)) {
            return ChatGenerationResult.success(generateGreetingResponse(normalizedQuestion));
        }

        // 2. Query Understanding
        QueryIntentClassifier.ClassificationResult classification = queryIntentClassifier.classify(question, normalizedQuestion);

        try {
            // 3. Derived active context (STATLESS)
            Set<String> activeDocs = deriveActiveDocsFromHistory(history);

            // 4. Hybrid Retrieval
            List<org.springframework.ai.document.Document> searchResults = retrievalService.retrieveContext(question, user, activeDocs);

            // 5. Early Exit (Business Fallback)
            if (searchResults.isEmpty()) {
                log.warn("[AI-CHAT] No context found for question: {}", question);
                return ChatGenerationResult.success("Rất tiếc, tôi hiện không tìm thấy đủ dữ kiện trong các tài liệu được cấp phép để trả lời chính xác câu hỏi này.");
            }

            // 6. Context Assembly with Metadata
            String context = assembleContextWithMetadata(searchResults, classification.titleTokens());
            
            logGenerationDiagnostics(sessionId, normalizedQuestion, classification, searchResults);

            // 7. Grounded AI Response
            String answer = generateAnswer(question, context, history, classification.intent());
            
            if (answer == null || answer.isBlank()) {
                log.error("[AI-CHAT] Empty model response. session={}", sessionId);
                return ChatGenerationResult.failed("ERR_EMPTY_RESPONSE", "Model returned empty answer", "Hệ thống gặp lỗi khi tạo câu trả lời.");
            }

            if (isSuspiciousFallback(answer, searchResults, classification.titleTokens())) {
                log.warn("[AI-DIAG-RED] Suspicious fallback detected despite strong context match! session={}, answer_len={}", 
                        sessionId, answer.length());
            }

            validateGrounding(sessionId, answer, searchResults);

            return ChatGenerationResult.success(answer);

        } catch (Exception e) {
            String errorCode = AiErrorClassifier.getErrorCode(e);
            boolean isHardQuota = AiErrorClassifier.isHardQuotaExceeded(e);
            
            log.error("[AI-CHAT] [Stage=FAILED] session={}, error={}, code={}", sessionId, e.getMessage(), errorCode);
            
            if (isHardQuota || AiErrorClassifier.isRetryableRateLimit(e)) {
                return ChatGenerationResult.retryableError(errorCode, e.getMessage(), 
                        "Hệ thống đang tạm thời gián đoạn do giới hạn hạn mức. Vui lòng thử lại sau.");
            }
            
            return ChatGenerationResult.failed(errorCode, e.getMessage(), "Xin lỗi, đã có lỗi hệ thống xảy ra.");
        }
    }

    @Retryable(
        retryFor = { org.springframework.ai.retry.TransientAiException.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 2000, multiplier = 2.0),
        noRetryFor = { java.lang.RuntimeException.class } 
    )
    private String generateAnswer(String question, String context, List<ChatMessage> history, QueryIntentClassifier.Intent intent) {
        String historyText = "";
        if (history != null && !history.isEmpty()) {
            // Trim history iteratively to keep prompt size manageable
            StringBuilder historySb = new StringBuilder();
            int maxHistoryChars = 2000; // Safe limit for history
            
            for (int i = history.size() - 1; i >= 0 && i >= history.size() - 6; i--) {
                ChatMessage m = history.get(i);
                if (m.getStatus() != null && m.getStatus() != MessageStatus.COMPLETED) continue;
                
                String entry = m.getRole() + ": " + m.getContent() + "\n";
                if (historySb.length() + entry.length() > maxHistoryChars) break;
                historySb.insert(0, entry);
            }
            historyText = historySb.toString();
        }

        String instruction = getInstructionByIntent(intent);

        String finalPrompt = String.format(
            "Bạn là một trợ lý AI doanh nghiệp cao cấp. %s\n\n" +
            "LỊCH SỬ TRÒ CHUYỆN:\n%s\n\n" +
            "NGỮ CẢNH HỢP LỆ (Dữ liệu từ hệ thống):\n%s\n\n" +
            "CÂU HỎI: %s\n\n" +
            "QUY TẮC PHẢI TUÂN THỦ:\n" +
            "1. Chỉ sử dụng thông tin từ 'NGỮ CẢNH HỢP LỆ' để trả lời.\n" +
            "2. Ưu tiên sử dụng thông tin từ 'TÀI LIỆU CHÍNH'.\n" +
            "3. Chỉ sử dụng 'TÀI LIỆU THAM KHẢO' nếu cần bổ sung hoặc làm rõ.\n" +
            "4. Không được trộn lẫn thông tin giữa các tài liệu nếu chúng nói về các đối tượng/thực thể khác nhau.\n" +
            "5. Trả lời chính xác, trung thực, không suy đoán ngoài dữ liệu.\n" +
            "6. Định dạng Markdown rõ ràng, chuyên nghiệp.",
            instruction, historyText, context, question
        );

        return chatModel.call(finalPrompt);
    }

    private String assembleContextWithMetadata(List<Document> searchResults, Set<String> titleTokens) {
        StringBuilder sb = new StringBuilder();
        
        List<Document> dominantDocs = searchResults.stream()
                .filter(d -> Boolean.TRUE.equals(d.getMetadata().get("is_dominant")))
                .collect(Collectors.toList());
        
        List<Document> referenceDocs = searchResults.stream()
                .filter(d -> !Boolean.TRUE.equals(d.getMetadata().get("is_dominant")))
                .collect(Collectors.toList());

        // Assembly with Budget Control (Waitstream 2)
        int initialDocCount = searchResults.size();
        int dominantSurvived = 0;
        int referenceSurvived = 0;
        int charsRemoved = 0;

        if (!dominantDocs.isEmpty()) {
            sb.append("=== TÀI LIỆU CHÍNH ===\n");
            for (Document doc : dominantDocs) {
                String docOutput = formatDocForContext(doc, titleTokens);
                // Dominant docs MUST survive as per Phase 3 rules, but we'll still keep an eye on total size
                sb.append(docOutput);
                dominantSurvived++;
            }
        }

        if (!referenceDocs.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("=== TÀI LIỆU THAM KHẢO ===\n");
            
            for (Document doc : referenceDocs) {
                String docOutput = formatDocForContext(doc, titleTokens);
                if (sb.length() + docOutput.length() > maxContextChars) {
                    log.info("[AI-METRICS] Context budget exceeded. Trimming reference chunk: '{}'", 
                            doc.getMetadata().getOrDefault("document_name", "unknown"));
                    charsRemoved += docOutput.length();
                    continue; // Skip excess chunks
                }
                sb.append(docOutput);
                referenceSurvived++;
            }
        }
        
        boolean trimmed = charsRemoved > 0;
        log.info("[AI-METRICS] Trimming decision: trimmed={}, chars_removed={}, docs_survived={}/{} (Dom:{}, Ref:{})", 
                trimmed, charsRemoved, (dominantSurvived + referenceSurvived), initialDocCount, dominantSurvived, referenceSurvived);
        
        return sb.toString();
    }

    private String formatDocForContext(Document doc, Set<String> titleTokens) {
        StringBuilder docSb = new StringBuilder();
        String title = (String) doc.getMetadata().getOrDefault("document_name", "Không rõ");
        String priority = "TIÊU CHUẨN";
        if (isExactTitleMatch(title, titleTokens)) {
            priority = "RẤT CAO";
        } else if (isPartialTitleMatch(title, titleTokens)) {
            priority = "CAO";
        }

        docSb.append(String.format("[TÀI LIỆU: %s | ƯU TIÊN: %s]\n", title, priority));
        docSb.append(doc.getContent());
        docSb.append("\n\n---\n\n");
        return docSb.toString();
    }

    private boolean isExactTitleMatch(String title, Set<String> tokens) {
        if (title == null || tokens == null) return false;
        String normalizedTitle = title.toLowerCase();
        return tokens.stream().anyMatch(normalizedTitle::equals);
    }

    private boolean isPartialTitleMatch(String title, Set<String> tokens) {
        if (title == null || tokens == null) return false;
        String normalizedTitle = title.toLowerCase();
        return tokens.stream().anyMatch(normalizedTitle::contains);
    }

    private double getScore(Document doc) {
        Object score = doc.getMetadata().get("score");
        if (score instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private void logGenerationDiagnostics(Long sessionId, String normalizedQuery, 
                                          QueryIntentClassifier.ClassificationResult classification, 
                                          List<Document> searchResults) {
        log.info("[AI-DIAG] [Session={}] Normalized Query: '{}'", sessionId, normalizedQuery);
        log.info("[AI-DIAG] [Session={}] Matched Tokens: {}", sessionId, classification.titleTokens());
        
        int exactMatchCount = 0;
        for (int i = 0; i < Math.min(5, searchResults.size()); i++) {
            Document doc = searchResults.get(i);
            String title = (String) doc.getMetadata().getOrDefault("document_name", "unknown");
            double score = getScore(doc);
            boolean isExact = isExactTitleMatch(title, classification.titleTokens());
            if (isExact) exactMatchCount++;

            log.info("[AI-DIAG] [Session={}] Chunk Ranking #{} - Title: '{}', Score: {}, ExactMatch: {}", 
                    sessionId, i + 1, title, score, isExact);
            
            if (isMojibake(title)) {
                log.warn("[AI-DIAG-WARN] [Session={}] Mojibake detected in title: '{}'", sessionId, title);
            }
        }
        
        log.info("[AI-DIAG] [Session={}] Chunks from exact title match: {}", sessionId, exactMatchCount);
    }

    private boolean isMojibake(String text) {
        if (text == null) return false;
        // Basic heuristic: contains corrupted markers like "ß╗▒" or "├ín" 
        // common in UTF-8 to CP1252 or vice versa mangling
        return text.contains("ß") || text.contains("├") || text.contains("┤") || text.contains("╗");
    }

    private boolean isSuspiciousFallback(String answer, List<Document> searchResults, Set<String> titleTokens) {
        if (answer == null || answer.length() > 150) return false;
        
        List<String> fallbacks = List.of("không tìm thấy", "không có dữ kiện", "không đủ dữ kiện");
        boolean isFallbackText = fallbacks.stream().anyMatch(answer.toLowerCase()::contains);
        
        if (isFallbackText && !searchResults.isEmpty()) {
            long exactMatchCount = searchResults.stream().filter(d -> isExactTitleMatch((String) d.getMetadata().get("document_name"), titleTokens)).count();
            long dominantCount = searchResults.stream().filter(d -> Boolean.TRUE.equals(d.getMetadata().get("is_dominant"))).count();
            
            // New Stable Signal: Exact Title Match exists AND we found multiple chunks for the dominant doc
            return exactMatchCount > 0 && dominantCount >= 2;
        }
        
        return false;
    }

    private void validateGrounding(Long sessionId, String answer, List<Document> searchResults) {
        if (answer == null || answer.length() < 50) return; // Guard: skip grounding drift check for short answers
        
        Optional<Document> dominantDoc = searchResults.stream()
                .filter(d -> Boolean.TRUE.equals(d.getMetadata().get("is_dominant")))
                .findFirst();

        if (dominantDoc.isPresent()) {
            String title = (String) dominantDoc.get().getMetadata().getOrDefault("document_name", "");
            String normalizedAnswer = answer.toLowerCase();
            
            // Soft Grounding-Drift Heuristic: Check if key tokens from title are present
            List<String> tokens = Arrays.stream(title.toLowerCase().split("[\\s_-]+"))
                    .filter(t -> t.length() > 2)
                    .collect(Collectors.toList());
            
            boolean hasGrounding = tokens.stream().anyMatch(normalizedAnswer::contains);
            
            if (!hasGrounding && tokens.size() >= 2) {
                log.warn("[AI-DIAG-YELLOW] [Session={}] Potential grounding drift! Answer may not reference dominant doc '{}'", 
                        sessionId, title);
            }
        }
    }

    private String getInstructionByIntent(QueryIntentClassifier.Intent intent) {
        return switch (intent) {
            case SPECIFIC_LOOKUP -> 
                "Câu hỏi này yêu cầu thông tin CỤ THỂ từ tài liệu. Hãy trích dẫn chính xác và đầy đủ các thông tin quan trọng nhất.";
            case ABSTRACT_EXPLANATION -> 
                "Câu hỏi này mang tính TỔNG QUAN. Hãy tổng hợp thông tin từ NHIỀU tài liệu/đoạn văn khác nhau trong ngữ cảnh để đưa ra câu trả lời bao quát và logic nhất.";
            case MIXED -> 
                "Hãy kết hợp giữa việc trích dẫn thông tin cụ thể và tổng hợp các ý chính từ ngữ cảnh để trả lời đầy đủ các khía cạnh của câu hỏi.";
        };
    }

    private Set<String> deriveActiveDocsFromHistory(List<ChatMessage> history) {
        return Collections.emptySet();
    }

    private boolean isGreeting(String text) {
        String cleanText = text.replaceAll("[!?.]$", "").trim();
        return GREETING_KEYWORDS.stream().anyMatch(kw -> cleanText.equalsIgnoreCase(kw));
    }

    private String generateGreetingResponse(String text) {
        if (text.contains("cảm ơn") || text.contains("thanks")) {
            return "Rất sẵn lòng giúp đỡ bạn! Nếu có câu hỏi nào khác, hãy cho tôi biết nhé.";
        }
        if (text.contains("tạm biệt") || text.contains("bye")) {
            return "Tạm biệt bạn! Hẹn gặp lại lần sau.";
        }
        return "Xin chào! Tôi có thể hỗ trợ gì cho bạn trong việc tra cứu thông tin hôm nay?";
    }

    public void cleanupSession(Long sessionId) {
        log.debug("cleanupSession called for session={} (no-op, stateless)", sessionId);
    }
}
