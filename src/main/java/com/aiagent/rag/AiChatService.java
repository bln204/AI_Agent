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

    // Câu chữ của 4 nút gợi ý (welcome-screen quick chips) trong dashboard.html.
    // Nếu đổi label các nút đó thì phải cập nhật lại 4 hằng số này tương ứng.
    private static final String QUICK_ACTION_WELCOME =
        com.aiagent.util.NormalizationUtils.normalizeForMatching("Xin chào! Tôi có thể hỏi gì?");
    private static final String QUICK_ACTION_DOCUMENTS =
        com.aiagent.util.NormalizationUtils.normalizeForMatching("Hướng dẫn sử dụng tài liệu");
    private static final String QUICK_ACTION_DEPARTMENTS =
        com.aiagent.util.NormalizationUtils.normalizeForMatching("Giới thiệu về các phòng ban");
    private static final String QUICK_ACTION_SUPPORT =
        com.aiagent.util.NormalizationUtils.normalizeForMatching("Tôi có thể làm gì với hệ thống này?");

    private final RagService ragService;
    private final com.aiagent.service.DocumentAccessService documentAccessService;
    private final com.aiagent.rag.analyzer.QueryAnalyzer queryAnalyzer;
    private final com.aiagent.rag.analyzer.MetadataVerificationService metadataVerificationService;
    private final com.aiagent.repository.DepartmentRepository departmentRepository;

    public ChatGenerationResult chat(Long sessionId, String question, User user, List<ChatMessage> history) {
        String normalizedQuestion = com.aiagent.util.NormalizationUtils.normalize(question);
        String matchKey = com.aiagent.util.NormalizationUtils.normalizeForMatching(question);
        log.info("[VERIFY-CHAT] session={}, questionLength={}", sessionId, question != null ? question.length() : 0);

        String quickActionResponse = tryQuickAction(matchKey);
        if (quickActionResponse != null) {
            return ChatGenerationResult.success(quickActionResponse);
        }

        if (isGreeting(normalizedQuestion)) {
            return ChatGenerationResult.success(generateGreetingResponse(normalizedQuestion));
        }

        try {
            String historyText = formatHistory(history);

            org.springframework.ai.vectorstore.filter.Filter.Expression filter = documentAccessService.buildVectorFilter(user);

            java.util.Set<String> candidates = queryAnalyzer.extractCandidates(normalizedQuestion);
            java.util.List<com.aiagent.rag.analyzer.DetectedEntity> entities = metadataVerificationService.verifyAndResolve(candidates);

            String response = ragService.processQuery(normalizedQuestion, user, filter, entities, historyText);

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

    // Trả lời dựng sẵn cho 4 nút gợi ý ở welcome-screen — KHÔNG đi qua RAG vì
    // đây là câu hỏi meta về cách dùng hệ thống, không phải câu hỏi tra cứu nội
    // dung tài liệu. Chỉ khớp chính xác câu chữ của từng nút nên không ảnh
    // hưởng tới hành vi RAG khi người dùng gõ câu hỏi tự nhiên.
    private String tryQuickAction(String matchKey) {
        if (QUICK_ACTION_WELCOME.equals(matchKey)) return buildWelcomeResponse();
        if (QUICK_ACTION_DOCUMENTS.equals(matchKey)) return buildDocumentHelpResponse();
        if (QUICK_ACTION_DEPARTMENTS.equals(matchKey)) return buildDepartmentIntroResponse();
        if (QUICK_ACTION_SUPPORT.equals(matchKey)) return buildSupportResponse();
        return null;
    }

    private String buildWelcomeResponse() {
        return "Xin chào! 👋 Tôi là **AI Agent Tri thức nội bộ** — trợ lý giúp bạn tra cứu nhanh thông tin, "
            + "quy trình và tài liệu nội bộ của công ty ngay trong khung chat này.\n\n"
            + "Bạn có thể hỏi tôi bất cứ điều gì liên quan đến tài liệu mà bạn được cấp quyền truy cập, "
            + "ví dụ như quy trình làm việc, chính sách phòng ban, hay tài liệu dự án bạn đang tham gia. "
            + "Tôi luôn trả lời dựa trên đúng những tài liệu bạn được phép xem, đảm bảo an toàn và chính xác.\n\n"
            + "Bạn muốn bắt đầu từ đâu?";
    }

    private String buildDocumentHelpResponse() {
        return "Bạn không cần vào một trang riêng để tìm tài liệu — chỉ cần **gõ câu hỏi trực tiếp vào khung chat "
            + "này** là tôi sẽ tự tìm trong những tài liệu bạn được phép xem và trả lời kèm nguồn tài liệu tương ứng.\n\n"
            + "Ví dụ bạn có thể hỏi: *\"Quy trình xin nghỉ phép như thế nào?\"* hoặc *\"Tài liệu hướng dẫn dự án X nằm ở đâu?\"*\n\n"
            + "Một vài điều bạn nên biết:\n"
            + "- Tài liệu **công khai** thì ai trong công ty cũng xem được.\n"
            + "- Tài liệu **phòng ban** chỉ người trong phòng ban đó mới xem được.\n"
            + "- Tài liệu **dự án** chỉ thành viên của dự án đó mới xem được.\n"
            + "- Tài liệu **riêng tư** chỉ người tạo (và Giám đốc) mới xem được.\n\n"
            + "Tôi sẽ luôn chỉ trả lời dựa trên đúng những tài liệu bạn có quyền truy cập, nên bạn có thể yên tâm hỏi thoải mái nhé!";
    }

    private String buildDepartmentIntroResponse() {
        List<com.aiagent.model.Department> departments = departmentRepository.findByActiveTrue();
        if (departments == null || departments.isEmpty()) {
            return "Hiện hệ thống chưa có phòng ban nào được cấu hình. Bạn có thể liên hệ quản trị viên nếu cần bổ sung.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Công ty mình hiện có các phòng ban sau:\n\n");
        for (com.aiagent.model.Department d : departments) {
            sb.append("- ").append(d.getName()).append("\n");
        }
        sb.append("\nMỗi phòng ban sẽ có những tài liệu và quy trình riêng. Nếu bạn muốn biết tài liệu của phòng ban "
            + "nào, cứ hỏi trực tiếp trong chat, tôi sẽ tìm những tài liệu bạn được phép xem nhé!");
        return sb.toString();
    }

    private String buildSupportResponse() {
        return "**AI Agent Tri thức nội bộ** là trợ lý AI giúp bạn tra cứu thông tin, tài liệu và quy trình nội bộ "
            + "của công ty một cách nhanh chóng, ngay trong khung chat này — không cần lục tìm qua nhiều hệ thống khác nhau.\n\n"
            + "Những điều tôi có thể giúp bạn:\n"
            + "- Trả lời câu hỏi dựa trên tài liệu nội bộ mà bạn được phép truy cập.\n"
            + "- Cung cấp nguồn tài liệu đi kèm câu trả lời để bạn tiện kiểm tra lại.\n"
            + "- Giữ lịch sử cuộc trò chuyện của riêng bạn, bạn có thể xem lại hoặc xóa bất cứ lúc nào.\n\n"
            + "Tôi luôn tôn trọng quyền truy cập của bạn: chỉ trả lời dựa trên tài liệu bạn được phép xem, và sẽ nói rõ "
            + "nếu chưa tìm thấy đủ thông tin thay vì tự bịa ra câu trả lời. Nếu cần hỗ trợ thêm, hãy hỏi tôi bất cứ lúc nào!";
    }

    public void cleanupSession(Long sessionId) {
        log.debug("cleanupSession called for session={} (no-op, stateless)", sessionId);
    }
}
