-- DocumentService.uploadDocument now records DIRECTOR/ADMIN uploads (which
-- skip the PENDING_APPROVAL queue and become APPROVED immediately) as
-- self-approved: approved_by = the uploader, approved_at = the upload time.
-- Backfill that same semantic onto pre-existing rows so RAG source citation
-- and the document detail page ("Duyệt bởi ...") aren't empty for documents
-- uploaded before this change.
--
-- Safe/precise because it targets exactly the rows this gap could have
-- affected: any APPROVED document with a NULL approved_by. A MANAGER's
-- document can never be in this state -- approveDocument() always sets
-- approved_by/approved_at together with the APPROVED status (see
-- DocumentRepository#approveIfPending) -- so every row this UPDATE touches
-- is, by construction, a DIRECTOR/ADMIN upload that self-approved at
-- creation time.
UPDATE documents
SET approved_by = uploaded_by, approved_at = created_at
WHERE status = 'APPROVED' AND approved_by IS NULL;
