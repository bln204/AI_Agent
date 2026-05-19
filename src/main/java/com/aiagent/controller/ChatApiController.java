package com.aiagent.controller;

import com.aiagent.dto.ChatMessageDto;
import com.aiagent.dto.ChatMessageResponse;
import com.aiagent.model.ChatMessage;
import com.aiagent.model.ChatSession;
import com.aiagent.model.MessageStatus;
import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.ChatService;
import com.aiagent.rag.AiChatService;
import com.aiagent.rag.ChatGenerationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/api/chat", produces = "application/json;charset=UTF-8")
@RequiredArgsConstructor
@Slf4j
public class ChatApiController {

    private final ChatService chatService;
    private final AiChatService aiChatService;
    private final UserRepository userRepository;

    // Lấy tất cả sessions của user hiện tại
    @GetMapping("/sessions")
    public ResponseEntity<List<ChatSession>> getSessions(Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) return ResponseEntity.status(401).build();
        List<ChatSession> sessions = chatService.getSessionsByUser(user.getId());
        return ResponseEntity.ok(sessions);
    }

    // Tạo session mới
    @PostMapping("/sessions")
    public ResponseEntity<ChatSession> createSession(Authentication authentication,
                                                      @RequestBody(required = false) Map<String, String> body) {
        User user = resolveUser(authentication);
        if (user == null) return ResponseEntity.status(401).build();
        String title = (body != null) ? body.getOrDefault("title", "Cuộc trò chuyện mới") : "Cuộc trò chuyện mới";
        ChatSession session = chatService.createSession(user, title);
        return ResponseEntity.ok(session);
    }

    // Lấy messages của 1 session
    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<List<ChatMessageDto>> getMessages(@PathVariable Long id, Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) return ResponseEntity.status(401).build();
        ChatSession session = chatService.getSession(id);
        if (session == null || !session.getUser().getId().equals(user.getId())) return ResponseEntity.status(403).build();
        
        List<ChatMessageDto> dtos = chatService.getMessages(id).stream()
            .map(m -> new ChatMessageDto(
                m.getId(),
                m.getRole(),
                m.getContent(),
                m.getStatus().name(),
                m.getErrorCode(),
                false
            ))
            .toList();
            
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/sessions/{id}/messages")
    public ResponseEntity<ChatMessageResponse> sendMessage(@PathVariable Long id,
                                                            @RequestBody Map<String, String> body,
                                                            Authentication authentication) {
        try {
            User user = resolveUser(authentication);
            if (user == null) return ResponseEntity.status(401).build();

            ChatSession session = chatService.getSession(id);
            if (!session.getUser().getId().equals(user.getId())) return ResponseEntity.status(403).build();

            String content = body.getOrDefault("content", "").trim();
            if (content.isEmpty()) return ResponseEntity.badRequest().build();

            log.info("[CHAT] Received query for session {}: '{}' (normalized: '{}')", 
                    id, content, com.aiagent.util.NormalizationUtils.normalize(content));

            String idempotencyKey = body.get("idempotencyKey");
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                log.warn("[SECURITY-GUARD] Rejecting request: missing idempotencyKey for session {}", id);
                ChatMessage pseudoMsg = new ChatMessage();
                pseudoMsg.setId(-1L);
                pseudoMsg.setStatus(MessageStatus.FAILED);
                pseudoMsg.setErrorCode("MISSING_IDEMPOTENCY_KEY");
                pseudoMsg.setContent("Yêu cầu không hợp lệ: thiếu khóa định danh.");
                return ResponseEntity.ok(toResponse(pseudoMsg, false));
            }

            ChatService.TurnResult turnResult;
            try {
                turnResult = chatService.startTurn(id, content, idempotencyKey);
            } catch (IllegalStateException busyEx) {
                if ("SESSION_BUSY".equals(busyEx.getMessage())) {
                    log.warn("Concurrency hit (409 Conflict -> 200 State): session={}, key={}", id, idempotencyKey);
                    ChatMessage pseudoMsg = new ChatMessage();
                    pseudoMsg.setId(-1L);
                    pseudoMsg.setStatus(MessageStatus.FAILED);
                    pseudoMsg.setErrorCode("SESSION_BUSY");
                    pseudoMsg.setContent("Hệ thống đang xử lý một câu hỏi khác trong cuộc trò chuyện này. Vui lòng đợi.");
                    return ResponseEntity.ok(toResponse(pseudoMsg, false));
                }
                throw busyEx;
            }

            if (turnResult.alreadyProcessed()) {
                ChatMessage existingAi = turnResult.aiPlaceholder();

                if (existingAi != null && existingAi.getStatus() == MessageStatus.COMPLETED) {
                    log.info("Idempotency hit (COMPLETED): session={}, key={}", id, idempotencyKey);
                    return ResponseEntity.ok(toResponse(existingAi, false));
                }

                if (existingAi != null && existingAi.getStatus() == MessageStatus.IN_PROGRESS) {
                    log.info("Idempotency hit (IN_PROGRESS): session={}, key={}", id, idempotencyKey);
                    return ResponseEntity.ok(toResponse(existingAi, false));
                }
                
                if (existingAi != null && (existingAi.getStatus() == MessageStatus.RETRYABLE_ERROR || existingAi.getStatus() == MessageStatus.FAILED)) {
                    log.info("Idempotency hit (RETRYABLE/FAILED -> RETRYING): session={}, key={}, status={}", 
                            id, idempotencyKey, existingAi.getStatus());
                }
            }

            ChatMessage aiPlaceholder = turnResult.aiPlaceholder();
            List<ChatMessage> history = chatService.getMessages(id);
            
            ChatGenerationResult result = aiChatService.chat(id, content, user, history);

            ChatMessage aiMsg = chatService.finalizeTurn(
                aiPlaceholder.getId(), 
                result.getContent(), 
                result.getStatus(),
                result.getErrorCode(),
                result.getErrorMessage()
            );

            return ResponseEntity.ok(toResponse(aiMsg, result.isRetryable()));

        } catch (Exception e) {
            log.error("Exception in ChatApiController.sendMessage: {}", e.getMessage(), e);
            ChatMessage errorMsg = new ChatMessage();
            errorMsg.setId(-1L);
            errorMsg.setStatus(MessageStatus.FAILED);
            errorMsg.setContent("Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau.");
            errorMsg.setErrorCode("INTERNAL_SERVER_ERROR");
            return ResponseEntity.ok(toResponse(errorMsg, false));
        }
    }

    private ChatMessageResponse toResponse(ChatMessage aiMsg, boolean retryable) {
        if (aiMsg.getStatus() == MessageStatus.COMPLETED && (aiMsg.getContent() == null || aiMsg.getContent().isBlank())) {
            log.error("[CRITICAL] EMPTY CONTENT WITH COMPLETED STATUS - MessageId={}", aiMsg.getId());
            aiMsg.setStatus(MessageStatus.FAILED);
            aiMsg.setErrorCode("EMPTY_COMPLETED_CONTENT");
            aiMsg.setContent("Hệ thống chưa tạo được phản hồi hợp lệ.");
        }

        log.info("[API-RESPONSE] messageId={}, status={}, contentLen={}, errorCode={}, retryable={}",
            aiMsg.getId(), 
            aiMsg.getStatus(), 
            (aiMsg.getContent() != null ? aiMsg.getContent().length() : 0),
            aiMsg.getErrorCode(), 
            retryable);

        return new ChatMessageResponse(
            aiMsg.getId(),
            aiMsg.getStatus().name(),
            aiMsg.getContent(),
            aiMsg.getErrorCode(),
            retryable
        );
    }

    // Xóa session
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable Long id, Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) return ResponseEntity.status(401).build();
        ChatSession session = chatService.getSession(id);
        if (!session.getUser().getId().equals(user.getId())) return ResponseEntity.status(403).build();
        chatService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    private User resolveUser(Authentication authentication) {
        if (authentication == null) return null;
        Object principal = authentication.getPrincipal();
        String email = null;
        if (principal instanceof OAuth2User oAuth2User) {
            email = oAuth2User.getAttribute("email");
        } else if (principal instanceof UserDetails userDetails) {
            email = userDetails.getUsername();
        }
        if (email == null) return null;
        return userRepository.findByEmail(email).orElse(null);
    }
}
