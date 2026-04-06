package com.aiagent.repository;

import com.aiagent.model.ChatMessage;
import com.aiagent.model.MessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByCreatedAt(Long sessionId);

    List<ChatMessage> findBySessionIdOrderBySequenceNumberAsc(Long sessionId);

    Optional<ChatMessage> findBySessionIdAndIdempotencyKey(Long sessionId, String idempotencyKey);

    List<ChatMessage> findBySessionIdAndStatus(Long sessionId, MessageStatus status);

    boolean existsBySessionIdAndStatusAndIdempotencyKeyNot(Long sessionId, MessageStatus status, String idempotencyKey);

    long countBySessionId(Long sessionId);
}
