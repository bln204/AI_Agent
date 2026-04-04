package com.aiagent.rag;

import com.aiagent.model.ChatMessage;
import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatService {

    private final ChatModel chatModel;
    private final UserRepository userRepository;
    private final RagRetrievalService retrievalService;

    // Cache for session context (active documents)
    private final Map<Long, SessionCacheEntry> sessionCache = new ConcurrentHashMap<>();

    private static final Set<String> GREETING_KEYWORDS = Set.of(
        "xin chào", "hi", "hello", "chào bạn", "chào buổi sáng", "chào buổi chiều", "chào buổi tối",
        "tạm biệt", "bye", "goodbye", "cảm ơn", "thank you", "thanks"
    );

    private static class SessionCacheEntry {
        Set<String> activeDocumentNames = new HashSet<>();
    }


    public String chat(Long sessionId, String question, Long userId, List<ChatMessage> history) {
        String normalizedQuestion = question.toLowerCase().trim();
        log.info("Processing chat Request: session={}, user={}, question='{}'", sessionId, userId, normalizedQuestion);

        // 1. Intent Detection - Cheap Path
        if (isGreeting(normalizedQuestion)) {
            return generateGreetingResponse(normalizedQuestion);
        }

        try {
            // 2. Resolve User and session context
            User user = (userId != null) ? userRepository.findById(userId).orElse(null) : null;
            SessionCacheEntry cacheEntry = (sessionId != null) ? 
                    sessionCache.computeIfAbsent(sessionId, k -> new SessionCacheEntry()) : null;

            // 3. Optimized Retrieval with threshold and scoring
            Set<String> activeDocs = (cacheEntry != null) ? cacheEntry.activeDocumentNames : Collections.emptySet();
            List<org.springframework.ai.document.Document> searchResults = retrievalService.retrieveContext(question, user, activeDocs);

            // 4. Update session cache with new documents
            if (cacheEntry != null && !searchResults.isEmpty()) {
                Set<String> newlyMatchedDocs = searchResults.stream()
                        .map(doc -> (String) doc.getMetadata().getOrDefault("document_name", "unknown"))
                        .filter(name -> !"unknown".equals(name))
                        .collect(Collectors.toSet());
                cacheEntry.activeDocumentNames.addAll(newlyMatchedDocs);
            }

            // 5. Context Quality Gate / Early Exit
            if (searchResults.isEmpty()) {
                log.warn("No quality context found for question: {}", question);
                return "Rất tiếc, tôi chưa tìm thấy thông tin cụ thể trả lời cho câu hỏi này trong hệ thống. Bạn có thể cung cấp thêm chi tiết hoặc thử lại với từ khóa khác.";
            }

            // 6. Build Context String with Truncation (Optimization)
            StringBuilder contextBuilder = new StringBuilder();
            int totalLength = 0;
            for (org.springframework.ai.document.Document doc : searchResults) {
                String content = doc.getContent();
                // Cap each chunk at 1200 chars
                if (content.length() > 1200) {
                    content = content.substring(0, 1200) + "... [truncated]";
                }
                
                if (totalLength + content.length() > 5000) {
                    log.debug("Total context limit reached (5000 chars), stopping append.");
                    break;
                }
                
                contextBuilder.append(content).append("\n\n");
                totalLength += content.length();
            }
            String context = contextBuilder.toString().trim();
            log.info("Final context length: {} chars, chunks used: {}", context.length(), searchResults.size());

            // 7. Generate Grounded AI Response - Final AI Call
            return generateAnswer(question, context, history);

        } catch (Exception e) {
            log.error("Error in AI RAG pipeline: {}", e.getMessage(), e);
            if (e.getMessage() != null && e.getMessage().contains("quota")) {
                return "Hệ thống AI hiện đang tạm thời quá tải hoặc hết hạn mức sử dụng. Vui lòng thử lại sau vài phút hoặc liên hệ quản trị viên.";
            }
            return "Xin lỗi, đã có lỗi hệ thống xảy ra. Vui lòng thử lại sau.";
        }
    }

    private boolean isGreeting(String text) {
        // Chuẩn hóa text: bỏ dấu câu ở cuối và trim
        String cleanText = text.replaceAll("[!?.]$", "").trim();
        // Chỉ coi là greeting nếu text khớp hoàn toàn với một trong các keywords chào hỏi
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



    private String generateAnswer(String question, String context, List<ChatMessage> history) {
        String historyText = "";
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 4);
            historyText = history.subList(start, history.size()).stream()
                    .map(m -> m.getRole() + ": " + m.getContent())
                    .collect(Collectors.joining("\n"));
        }

        String finalPrompt = String.format(
            "Bạn là một trợ lý AI doanh nghiệp. Hãy trả lời câu hỏi DỰA TRÊN THÔNG TIN ĐƯỢC CUNG CẤP.\n\n" +
            "LỊCH SỬ TRÒ CHUYỆN:\n%s\n\n" +
            "NGỮ CẢNH HỢP LỆ:\n%s\n\n" +
            "CÂU HỎI: %s\n\n" +
            "QUY TẮC:\n" +
            "1. CHỈ sử dụng thông tin từ 'NGỮ CẢNH HỢP LỆ'.\n" +
            "2. Nếu không có đủ thông tin, hãy nói 'Tôi hiện không tìm thấy đủ dữ kiện trong các tài liệu được cấp phép để trả lời chính xác câu hỏi này'.\n" +
            "3. Không tiết lộ tên file hay sự tồn tại của các tài liệu khác.\n" +
            "4. Trả lời chuyên nghiệp, lịch sự.",
            historyText, context, question
        );

        return chatModel.call(finalPrompt);
    }

    public void cleanupSession(Long sessionId) {
        if (sessionId != null) {
            log.info("Cleaning up session cache for session: {}", sessionId);
            sessionCache.remove(sessionId);
        }
    }
}

