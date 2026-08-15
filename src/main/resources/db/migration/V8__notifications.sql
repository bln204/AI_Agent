-- Notification (Phase 6, decision #8): DB-persisted + polling, no
-- WebSocket/SSE/message broker. Fan-out is one row per recipient per event
-- (director notified of a pending upload, uploader notified of the
-- approve/reject decision) rather than a shared row with per-user read
-- state, matching NotificationService's one-row-per-recipient writes.
CREATE TABLE notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipient_id BIGINT NOT NULL,
    document_id BIGINT NULL,
    message VARCHAR(500) NOT NULL,
    is_read TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    INDEX idx_notifications_recipient_unread (recipient_id, is_read),
    INDEX idx_notifications_recipient_created (recipient_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
