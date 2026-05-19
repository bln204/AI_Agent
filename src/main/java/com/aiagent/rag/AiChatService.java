package com.aiagent.rag;

import com.aiagent.model.ChatMessage;
import com.aiagent.model.MessageStatus;
import com.aiagent.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatService {

    private static final Set<String> GREETING_KEYWORDS = Set.of(
        "xin chào", "hi", "hello", "chào bạn", "chào buổi sáng", "chào buổi chiều", "chào buổi tối",
        "tạm biệt", "bye", "goodbye", "cảm ơn", "thank you", "thanks"
    );

    private final RagService ragService;
    private final com.aiagent.service.DocumentAccessService documentAccessService;
    
    public ChatGenerationResult chat(Long sessionId, String question, User user, List<ChatMessage> history) {
        String normalizedQuestion = com.aiagent.util.NormalizationUtils.normalize(question);
        log.info("[AI-CHAT] session={}, user={}, question='{}'", 
                sessionId, user != null ? user.getEmail() : "Guest", question);

        if (isGreeting(normalizedQuestion)) {
            return ChatGenerationResult.success(generateGreetingResponse(normalizedQuestion));
        }

        try {
            String historyText = formatHistory(history);

            org.springframework.ai.vectorstore.filter.Filter.Expression filter = documentAccessService.buildVectorFilter(user);

            String response = ragService.processQuery(question, user, filter, historyText);

            return ChatGenerationResult.success(response);

        } catch (Exception e) {
            String errorCode = AiErrorClassifier.getErrorCode(e);
            log.error("[AI-CHAT] [FAILED] session={}, error={}", sessionId, e.getMessage());
            return ChatGenerationResult.failed(errorCode, e.getMessage(), "Hệ thống gặp lỗi khi xử lý yêu cầu.");
        }
    }

    private String formatHistory(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int maxChars = 2000;
        for (int i = history.size() - 1; i >= 0 && i >= history.size() - 5; i--) {
            ChatMessage m = history.get(i);
            if (m.getStatus() != null && m.getStatus() != MessageStatus.COMPLETED) continue;
            String entry = m.getRole() + ": " + m.getContent() + "\n";
            if (sb.length() + entry.length() > maxChars) break;
            sb.insert(0, entry);
        }
        return sb.toString();
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
