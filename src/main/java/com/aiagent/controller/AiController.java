package com.aiagent.controller;

import com.aiagent.model.ChatMessage;
import com.aiagent.model.User;
import com.aiagent.rag.AiChatService;
import com.aiagent.rag.ChatGenerationResult;

import com.aiagent.repository.UserRepository;
import com.aiagent.service.ChatService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping(value = "/ai", produces = "application/json;charset=UTF-8")
@RequiredArgsConstructor
@Slf4j
public class AiController {

    private final AiChatService aiChatService;
    private final com.aiagent.service.DocumentService documentService;
    private final ChatService chatService;
    private final UserRepository userRepository;

    @PostMapping("/ingest-document")
    public ResponseEntity<String> ingestDocument(@RequestParam("file") MultipartFile file) {
        try {
            User user = getCurrentUser();
            // PRIVATE scope was removed (business decision); this endpoint has
            // no scope-selection UI (unreferenced by any frontend page), so
            // fall back to the closest equivalent to the old "only me" intent
            // that remains valid: DEPARTMENT scoped to the caller's own
            // department. Callers with no department assigned fall back to
            // PUBLIC -- DEPARTMENT would otherwise fail the empty-departmentIds
            // validation below with no narrower scope left to use instead.
            boolean hasDepartment = user.getDepartment() != null;
            com.aiagent.model.AccessLevel scope = hasDepartment
                    ? com.aiagent.model.AccessLevel.DEPARTMENT
                    : com.aiagent.model.AccessLevel.PUBLIC;
            java.util.List<Long> deptIds = hasDepartment
                    ? java.util.Collections.singletonList(user.getDepartment().getId())
                    : java.util.Collections.emptyList();
            documentService.uploadDocument(
                    file.getOriginalFilename(),
                    "",
                    deptIds,
                    java.util.Collections.emptyList(),
                    scope,
                    null,
                    com.aiagent.model.DocumentClassification.OTHER,
                    null,
                    "Uploaded via AI Chat",
                    true,
                    file, 
                    user);
            
            return ResponseEntity.ok("Document ingested successfully: " + file.getOriginalFilename());
        } catch (Exception e) {
            log.error("Error ingesting document", e);
            return ResponseEntity.internalServerError().body("Không thể tải tài liệu lên. Vui lòng kiểm tra lại tệp.");
        }
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        try {
            User user = getCurrentUser();
            List<ChatMessage> history = null;
            if (request.getSessionId() != null) {
                history = chatService.getMessages(request.getSessionId());
            }
            ChatGenerationResult result = aiChatService.chat(request.getSessionId(), request.getQuestion(), user, history);
            return ResponseEntity.ok(new ChatResponse(result.getContent()));
        } catch (Exception e) {
            log.error("Error during AI chat", e);
            return ResponseEntity.internalServerError()
                    .body(new ChatResponse("Hệ thống gặp lỗi khi xử lý yêu cầu. Vui lòng thử lại sau."));
        }
    }

    @GetMapping("/chat-history")
    public ResponseEntity<List<ChatMessage>> getChatHistory(@RequestParam("sessionId") Long sessionId) {
        try {
            List<ChatMessage> history = chatService.getMessages(sessionId);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            log.error("Error fetching chat history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetails)) {
            throw new RuntimeException("User not authenticated");
        }
        String email = ((UserDetails) authentication.getPrincipal()).getUsername();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found in database"));
    }

    @Data
    public static class ChatRequest {
        private String question;
        private Long sessionId;
    }

    @Data
    @RequiredArgsConstructor
    public static class ChatResponse {
        private final String answer;
    }
}
