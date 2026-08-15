-- Approval lifecycle: documents uploaded by MANAGER require DIRECTOR approval
-- before RAG ingestion (Qdrant embedding). status defaults to APPROVED so all
-- existing rows -- and any insert that doesn't explicitly set status --
-- keep today's "visible/ingested immediately" behavior unchanged.
ALTER TABLE documents ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE documents ADD COLUMN approved_by BIGINT NULL;
ALTER TABLE documents ADD COLUMN approved_at DATETIME NULL;

-- Business decision: PRIVATE access scope removed -- only PUBLIC / DEPARTMENT
-- / PROJECT remain. Existing PRIVATE documents are migrated to DEPARTMENT,
-- scoped to the uploader's own department (closest equivalent to the old
-- "only the uploader can see this" semantics). Uploaders with no department
-- assigned fall back to the 'ALL' department -- same fallback
-- DataMigrationService.migrateDocuments() already uses for legacy documents
-- with no department.
--
-- Step 1 links documents BEFORE access_level is overwritten, so the
-- access_level = 'PRIVATE' filter below is still meaningful.
INSERT INTO document_departments (document_id, department_id)
SELECT d.id, COALESCE(u.department_id, (SELECT id FROM departments WHERE code = 'ALL'))
FROM documents d
JOIN users u ON d.uploaded_by = u.id
WHERE d.access_level = 'PRIVATE'
  AND NOT EXISTS (SELECT 1 FROM document_departments dd WHERE dd.document_id = d.id);

UPDATE documents SET access_level = 'DEPARTMENT' WHERE access_level = 'PRIVATE';

-- Before applying this migration to production, verify no PRIVATE row
-- survived Step 1/2 (should return 0):
--   SELECT COUNT(*) FROM documents WHERE access_level = 'PRIVATE';
-- If it doesn't, the MODIFY COLUMN below would silently coerce the
-- remaining rows to MySQL's ENUM default (first listed value) instead of
-- failing loudly -- do not proceed until the count is 0.
ALTER TABLE documents MODIFY COLUMN access_level ENUM('PUBLIC','DEPARTMENT','PROJECT') NOT NULL;

-- New DocumentClassification value REQUEST_FORM ("Đơn từ") needs no schema
-- change: classification is already VARCHAR(50) (see V4), not a native ENUM.
