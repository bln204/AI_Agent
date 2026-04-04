package com.aiagent.service;

import com.aiagent.model.ChatMessage;
import com.aiagent.model.ChatSession;
import com.aiagent.model.User;
import com.aiagent.repository.ChatMessageRepository;
import com.aiagent.repository.ChatSessionRepository;
import com.aiagent.rag.AiChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final AiChatService aiChatService;

    public List<ChatSession> getSessionsByUser(Long userId) {
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional
    public ChatSession createSession(User user, String title) {
        ChatSession session = new ChatSession();
        session.setUser(user);
        session.setTitle(title != null && !title.isBlank() ? title : "Cuộc trò chuyện mới");
        return sessionRepository.save(session);
    }

    public ChatSession getSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session không tồn tại: " + sessionId));
    }

    public List<ChatMessage> getMessages(Long sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAt(sessionId);
    }

    @Transactional
    public ChatMessage addMessage(Long sessionId, String role, String content) {
        ChatSession session = getSession(sessionId);

        // Update Title ONLY if it's the first USER message and session title is default
        if ("USER".equals(role)) {
            List<ChatMessage> existing = messageRepository.findBySessionIdOrderByCreatedAt(sessionId);
            if (existing.isEmpty() && (session.getTitle() == null || session.getTitle().equals("Cuộc trò chuyện mới"))) {
                String autoTitle = content.length() > 40 ? content.substring(0, 40) + "..." : content;
                session.setTitle(autoTitle);
                sessionRepository.save(session); // Only save if title changed
            }
        }

        // updatedAt is handled by @PreUpdate in ChatSession during message persistence
        // because ChatSession is the parent. If not, we can explicitly touch it once.
        // But messageRepository.save(msg) with CASCADE should handle it if set up.
        // To be safe and minimal, we don't need redundant save() here if nothing changed.

        ChatMessage msg = new ChatMessage();
        msg.setSession(session);
        msg.setRole(role);
        msg.setContent(content);
        return messageRepository.save(msg);
    }

    @Transactional
    public void deleteSession(Long sessionId) {
        ChatSession session = getSession(sessionId);
        
        // Use sessionRepository.delete(entity) to trigger cascade correctly
        sessionRepository.delete(session);
        
        // Register cache cleanup to run ONLY after successful transaction commit
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    aiChatService.cleanupSession(sessionId);
                }
            });
        } else {
            // Fallback for non-transactional calls (unlikely here but safe)
            aiChatService.cleanupSession(sessionId);
        }
        
        log.info("Session {} and its messages marked for deletion.", sessionId);
    }
}
