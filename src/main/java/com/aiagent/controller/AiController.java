package com.aiagent.controller;

import com.aiagent.model.ChatMessage;
import com.aiagent.model.User;
import com.aiagent.rag.AiChatService;

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
@RequestMapping("/ai")
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
            // Ingest as PRIVATE specifically for this user
            documentService.uploadDocument(
                    file.getOriginalFilename(), 
                    "", 
                    java.util.Collections.emptyList(), 
                    java.util.Collections.emptyList(), 
                    com.aiagent.model.AccessLevel.PRIVATE, 
                    file, 
                    user);
            
            return ResponseEntity.ok("Document ingested successfully: " + file.getOriginalFilename());
        } catch (Exception e) {
            log.error("Error ingesting document", e);
            return ResponseEntity.internalServerError().body("Error ingesting document: " + e.getMessage());
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
            String response = aiChatService.chat(request.getSessionId(), request.getQuestion(), user.getId(), history);
            return ResponseEntity.ok(new ChatResponse(response));
        } catch (Exception e) {
            log.error("Error during AI chat", e);
            return ResponseEntity.internalServerError().body(new ChatResponse("Error: " + e.getMessage()));
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
