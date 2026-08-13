-- V5: Document duplicate detection (file + content hash)
--
-- NOTE (same as V1-V4): this project has no Flyway/Liquibase dependency.
-- In dev, spring.jpa.hibernate.ddl-auto=update creates these columns
-- automatically from the Document entity on next startup. In production
-- (ddl-auto=validate), this script must be applied manually before
-- deploying the corresponding application version — see README.
--
-- Both columns are nullable: pre-existing documents were never hashed and
-- simply do not participate in duplicate detection until re-uploaded or
-- backfilled (backfilling is a separate, not-yet-implemented task — it
-- would require re-reading each document's physical file from disk).
-- MySQL unique indexes permit multiple NULLs, so this does not conflict
-- with the UNIQUE constraint below.

ALTER TABLE documents ADD COLUMN file_hash VARCHAR(64) NULL;
ALTER TABLE documents ADD COLUMN content_hash VARCHAR(64) NULL;

-- Race-condition guard (see DocumentService#uploadDocument /
-- DocumentDuplicateDetectionService): the application-level existsBy* check
-- alone cannot prevent two concurrent requests from both passing the check
-- before either commits. This constraint is the actual protection —
-- DocumentService catches the resulting DataIntegrityViolationException and
-- translates it into the same duplicate-rejection response.
-- UNIQUE already creates a backing index in MySQL/InnoDB, so no separate
-- CREATE INDEX is needed (unlike the plain non-unique indexes in V4).
ALTER TABLE documents ADD CONSTRAINT uk_documents_file_hash UNIQUE (file_hash);
ALTER TABLE documents ADD CONSTRAINT uk_documents_content_hash UNIQUE (content_hash);
