package com.aiagent.repository;

import com.aiagent.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientIdOrderByCreatedAtDesc(Long recipientId, Pageable pageable);

    long countByRecipientIdAndIsReadFalse(Long recipientId);

    // IDOR guard: a notification can only be marked read by looking it up
    // scoped to its own recipient, never by id alone.
    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);
}
