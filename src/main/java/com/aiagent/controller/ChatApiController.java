package com.aiagent.controller;

import com.aiagent.model.ChatMessage;
import com.aiagent.model.ChatSession;
import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.ChatService;
import com.aiagent.rag.AiChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
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
    public ResponseEntity<List<ChatMessage>> getMessages(@PathVariable Long id, Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) return ResponseEntity.status(401).build();
        // Kiểm tra session thuộc về user này
        ChatSession session = chatService.getSession(id);
        if (!session.getUser().getId().equals(user.getId())) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(chatService.getMessages(id));
    }

    // Gửi tin nhắn (user) và nhận AI response
    @PostMapping("/sessions/{id}/messages")
    public ResponseEntity<Map<String, Object>> sendMessage(@PathVariable Long id,
                                                            @RequestBody Map<String, String> body,
                                                            Authentication authentication) {
        try {
            User user = resolveUser(authentication);
            if (user == null) return ResponseEntity.status(401).build();

            ChatSession session = chatService.getSession(id);
            if (!session.getUser().getId().equals(user.getId())) return ResponseEntity.status(403).build();

            String content = body.getOrDefault("content", "").trim();
            if (content.isEmpty()) return ResponseEntity.badRequest().build();

            // Fetch history BEFORE saving the current user message to avoid duplicate context
            List<ChatMessage> history = chatService.getMessages(id);

            // Lưu tin nhắn của user
            ChatMessage userMsg = chatService.addMessage(id, "USER", content);

            // Call RAG AI Service
            String aiResponse = aiChatService.chat(id, content, user.getId(), history);
            
            // Lưu tin nhắn của AI
            ChatMessage aiMsg = chatService.addMessage(id, "AI", aiResponse);

            return ResponseEntity.ok(Map.of(
                "userMessage", Map.of("id", userMsg.getId(), "role", "USER", "content", content),
                "aiMessage",   Map.of("id", aiMsg.getId(),   "role", "AI",   "content", aiResponse)
            ));
        } catch (Exception e) {
            // Log chi tiết lỗi và trả về JSON thân thiện để frontend không bị SyntaxError parse HTML 500
            System.err.println("Exception in ChatApiController: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.ok(Map.of(
                "error", true,
                "aiMessage", Map.of("id", -1, "role", "AI", "content", "Xin lỗi, đã có lỗi hệ thống xảy ra. Vui lòng thử lại sau.")
            ));
        }
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
