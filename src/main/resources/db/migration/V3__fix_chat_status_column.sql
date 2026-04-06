-- ============================================================
-- Migration: Fix chat_messages.status column length (Hotfix SQL-1265)
-- ============================================================

-- 1. Modify the column to accommodate 'IN_PROGRESS' (11 chars) and ensure it's NOT NULL.
-- MySQL 8: Changing VARCHAR length is generally an INPLACE operation.
ALTER TABLE chat_messages MODIFY COLUMN status VARCHAR(32) NOT NULL;

-- 2. Cleanup legacy data (PENDING -> FAILED)
-- This ensures 'zombie' records don't block New Turns via existing Concurrency Guards.
UPDATE chat_messages SET status = 'FAILED' WHERE status = 'PENDING';
