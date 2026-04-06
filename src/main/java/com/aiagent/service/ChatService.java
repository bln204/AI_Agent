package com.aiagent.service;

import com.aiagent.model.ChatMessage;
import com.aiagent.model.ChatSession;
import com.aiagent.model.MessageStatus;
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
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Backoff;
import org.hibernate.exception.LockAcquisitionException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.Optional;

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
        return messageRepository.findBySessionIdOrderBySequenceNumberAsc(sessionId);
    }

    // ============================================================
    // STATE MACHINE: Phase 1 — Start Turn (Atomic, <50ms)
    // ============================================================
    /**
     * Atomically creates a USER message and a PENDING AI placeholder.
     * Uses idempotency key to prevent duplicate processing.
     * 
     * Returns a TurnResult containing:
     * - userMessage: the saved USER message
     * - aiPlaceholder: the PENDING AI message (to be finalized later)
     * - alreadyProcessed: true if this idempotencyKey was seen before
     */
    @Transactional
    @Retryable(
        retryFor = {LockAcquisitionException.class, ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public TurnResult startTurn(Long sessionId, String content, String idempotencyKey) {
        log.debug("startTurn: session={}, idempotencyKey={}", sessionId, idempotencyKey);

        // 1. SERIALIZE: Lock the session to prevent parallel turns for the same session
        // Only one thread can proceed past this point for the given sessionId.
        ChatSession session = sessionRepository.findByIdWithLock(sessionId)
                .orElseThrow(() -> new RuntimeException("Session không tồn tại: " + sessionId));

        // 2. IDEMPOTENCY: Check if THIS specific key was already processed or is running
        Optional<ChatMessage> existing = messageRepository.findBySessionIdAndIdempotencyKey(sessionId, idempotencyKey);
        if (existing.isPresent()) {
            ChatMessage existingMsg = existing.get();
            log.info("Idempotency hit: session={}, key={}, status={}", sessionId, idempotencyKey, existingMsg.getStatus());
            
            // Find the AI pair (next sequence)
            List<ChatMessage> allMessages = messageRepository.findBySessionIdOrderBySequenceNumberAsc(sessionId);
            ChatMessage aiPair = allMessages.stream()
                .filter(m -> m.getSequenceNumber() != null && existingMsg.getSequenceNumber() != null 
                        && m.getSequenceNumber().equals(existingMsg.getSequenceNumber() + 1)
                        && "AI".equals(m.getRole()))
                .findFirst()
                .orElse(null);
            
            return new TurnResult(existingMsg, aiPair, true);
        }

        // 3. CONCURRENCY GUARD: Is there ANOTHER active turn (different key) in this session?
        // This prevents parallel LLM calls but allows idempotency retries for the SAME key.
        boolean isBusy = messageRepository.existsBySessionIdAndStatusAndIdempotencyKeyNot(
                sessionId, MessageStatus.IN_PROGRESS, idempotencyKey);
        
        if (isBusy) {
            log.warn("[CONCURRENCY-REJECT] Session {} is already processing another turn.", sessionId);
            throw new IllegalStateException("SESSION_BUSY");
        }

        // 4. Update sequence counter
        long currentSeq = session.getLastSequenceNumber() != null ? session.getLastSequenceNumber() : 0L;
        long userSeq = currentSeq + 1;
        long aiSeq = currentSeq + 2;
        session.setLastSequenceNumber(aiSeq);

        // 5. Auto-title
        if (countBySessionId(sessionId) == 0 && isDefaultTitle(session.getTitle())) {
            String autoTitle = content.length() > 40 ? content.substring(0, 40) + "..." : content;
            session.setTitle(autoTitle);
        }

        sessionRepository.save(session);

        // 6. Insert USER message (COMPLETED)
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSession(session);
        userMsg.setRole("USER");
        userMsg.setContent(content);
        userMsg.setIdempotencyKey(idempotencyKey);
        userMsg.setStatus(MessageStatus.COMPLETED);
        userMsg.setSequenceNumber(userSeq);
        messageRepository.save(userMsg);

        // 7. Insert AI placeholder (IN_PROGRESS)
        ChatMessage aiPlaceholder = new ChatMessage();
        aiPlaceholder.setSession(session);
        aiPlaceholder.setRole("AI");
        aiPlaceholder.setContent(""); 
        aiPlaceholder.setStatus(MessageStatus.IN_PROGRESS);
        aiPlaceholder.setSequenceNumber(aiSeq);
        aiPlaceholder.setIdempotencyKey(idempotencyKey); // Also tag AI message with same key
        messageRepository.save(aiPlaceholder);

        log.info("Turn started: session={}, userSeq={}, aiSeq={}", sessionId, userSeq, aiSeq);
        return new TurnResult(userMsg, aiPlaceholder, false);
    }

    // ============================================================
    // STATE MACHINE: Phase 3 — Finalize Turn (Atomic, <10ms)
    // ============================================================
    /**
     * Updates the PENDING AI placeholder with actual LLM content.
     * Called AFTER the external AI call completes (outside any transaction).
     */
    @Transactional
    @Retryable(
        retryFor = {LockAcquisitionException.class, ObjectOptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public ChatMessage finalizeTurn(Long aiMessageId, String aiContent, MessageStatus finalStatus, String errorCode, String errorMessage) {
        ChatMessage aiMsg = messageRepository.findById(aiMessageId)
                .orElseThrow(() -> new RuntimeException("AI message placeholder not found: " + aiMessageId));

        if (aiMsg.getStatus() == MessageStatus.COMPLETED) {
            log.warn("AI message {} already COMPLETED, skipping finalization.", aiMessageId);
            return aiMsg;
        }

        // STRICT CONTRACT VALIDATION
        if (finalStatus == MessageStatus.COMPLETED && (aiContent == null || aiContent.isBlank())) {
            log.error("[STRICT-CONTRACT] COMPLETED with empty content. messageId={}, errorCode={}", aiMessageId, errorCode);
            finalStatus = MessageStatus.FAILED;
            aiContent = "[ERR_EMPTY_COMPLETED] Hệ thống gặp lỗi nội bộ.";
            errorCode = "ERR_CONTRACT_VIOLATION";
        }

        aiMsg.setContent(aiContent);
        aiMsg.setStatus(finalStatus);
        aiMsg.setErrorCode(errorCode);
        aiMsg.setErrorMessage(errorMessage);

        ChatMessage saved = messageRepository.save(aiMsg);
        log.info("[CHAT] Stage=FINALIZE messageId={}, status={}, errorCode={}", aiMessageId, finalStatus, errorCode);
        return saved;
    }

    // ============================================================
    // Legacy method — kept for backward compatibility during migration
    // ============================================================
    @Transactional
    @Retryable(
        retryFor = {LockAcquisitionException.class, ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public ChatMessage addMessage(Long sessionId, String role, String content) {
        ChatSession session = getSession(sessionId);

        ChatMessage msg = new ChatMessage();
        msg.setSession(session);
        msg.setRole(role);
        msg.setContent(content);
        msg.setStatus(MessageStatus.COMPLETED);

        // Assign sequence number
        long currentSeq = session.getLastSequenceNumber() != null ? session.getLastSequenceNumber() : 0L;
        long nextSeq = currentSeq + 1;
        session.setLastSequenceNumber(nextSeq);
        msg.setSequenceNumber(nextSeq);

        sessionRepository.save(session);
        return messageRepository.save(msg);
    }

    @Transactional
    @Retryable(retryFor = {LockAcquisitionException.class}, maxAttempts = 2)
    public void deleteSession(Long sessionId) {
        ChatSession session = getSession(sessionId);

        sessionRepository.delete(session);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    aiChatService.cleanupSession(sessionId);
                }
            });
        } else {
            aiChatService.cleanupSession(sessionId);
        }

        log.info("Session {} and its messages deleted.", sessionId);
    }

    // ============================================================
    // Helper Methods
    // ============================================================
    private boolean isDefaultTitle(String title) {
        return title == null || title.equals("Cuộc trò chuyện mới");
    }

    private long countBySessionId(Long sessionId) {
        return messageRepository.countBySessionId(sessionId);
    }

    /**
     * Value object holding the result of a Turn start.
     */
    public record TurnResult(
        ChatMessage userMessage,
        ChatMessage aiPlaceholder,
        boolean alreadyProcessed
    ) {}
}
