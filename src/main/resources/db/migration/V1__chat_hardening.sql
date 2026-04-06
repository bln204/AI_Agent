-- ============================================================
-- Migration: Chat System Hardening (Production)
-- ============================================================
-- Phase 1: Add new columns (nullable, no constraints)
-- Phase 2: Backfill existing data
-- Phase 3: Add constraints
-- ============================================================

-- ─── PHASE 1: Add Columns ───────────────────────────────────
-- ChatSession: sequence counter
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS last_sequence_number BIGINT DEFAULT 0;

-- ChatMessage: idempotency, status, ordering
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'COMPLETED';
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS sequence_number BIGINT;

-- ─── PHASE 2: Backfill Existing Data ────────────────────────
-- Give each existing message a unique generated idempotency key
UPDATE chat_messages SET idempotency_key = CONCAT('legacy-', id) WHERE idempotency_key IS NULL;

-- Assign sequence numbers to existing messages ordered by created_at
SET @session_id = 0;
SET @seq = 0;
UPDATE chat_messages cm
JOIN (
    SELECT id,
           session_id,
           @seq := IF(@session_id = session_id, @seq + 1, 1) AS new_seq,
           @session_id := session_id
    FROM chat_messages
    ORDER BY session_id, created_at
) ranked ON cm.id = ranked.id
SET cm.sequence_number = ranked.new_seq;

-- Update last_sequence_number on sessions
UPDATE chat_sessions cs
SET cs.last_sequence_number = (
    SELECT COALESCE(MAX(cm.sequence_number), 0) 
    FROM chat_messages cm 
    WHERE cm.session_id = cs.id
);

-- ─── PHASE 3: Add Constraints ───────────────────────────────
-- Unique index for idempotency per session
CREATE UNIQUE INDEX IF NOT EXISTS idx_session_idempotency 
    ON chat_messages(session_id, idempotency_key);

-- Index for ordered message retrieval
CREATE INDEX IF NOT EXISTS idx_session_sequence 
    ON chat_messages(session_id, sequence_number);

-- Index for status queries (e.g., finding PENDING messages)
CREATE INDEX IF NOT EXISTS idx_message_status 
    ON chat_messages(status);

-- ─── PHASE 4: Character Set Migration (UTF-8 full support) ──
ALTER TABLE chat_messages CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE chat_sessions CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
