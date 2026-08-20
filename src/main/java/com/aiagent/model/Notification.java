package com.aiagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    // Plain id, not a JPA @ManyToOne to Document -- notifications must keep
    // rendering (and their documentId, for the "view" link) even if the
    // referenced document is later hard-deleted (DocumentService.deleteDocument
    // performs a real delete, not a soft-delete), so this must never trigger a
    // lazy-load/FK failure on an already-gone row.
    @Column(name = "document_id")
    private Long documentId;

    // Cùng lý do như documentId ở trên: plain id (không @ManyToOne) để
    // notification vẫn render được kể cả khi project bị xoá sau đó.
    @Column(name = "project_id")
    private Long projectId;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
