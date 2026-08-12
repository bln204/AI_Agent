-- ============================================================
-- Migration: Additional Chat Deduplication & Performance
-- ============================================================

-- 1. Ensure the unique constraint on idempotency is strictly enforced
-- This is our final safety net against any application-level race conditions.
-- We use a unique index which acts as a constraint in most RDBMS (H2, MySQL, PostgreSQL).
CREATE UNIQUE INDEX idx_session_idempotency_unique
    ON chat_messages(session_id, idempotency_key);

-- 2. Performance index for the concurrency guard (Checking PENDING/IN_PROGRESS turns)
-- This speeds up 'existsBySessionIdAndStatusAndIdempotencyKeyNot'
CREATE INDEX idx_session_status_concurrency
    ON chat_messages(session_id, status);

-- 3. Cleanup: Rename any PENDING statuses to IN_PROGRESS if we are migrating existing data
UPDATE chat_messages SET status = 'IN_PROGRESS' WHERE status = 'PENDING';
