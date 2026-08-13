-- Trạng thái pipeline Document Viewer (PENDING/PROCESSING/READY/FAILED/
-- UNSUPPORTED). Backfill dựa trên dữ liệu hiện có: đã có viewer_file_path
-- thì coi là READY; định dạng không phải PDF/DOCX thì UNSUPPORTED (viewer
-- không áp dụng). DOCX còn lại (viewer_file_path null) giữ PENDING mặc định
-- — không suy đoán FAILED cho dữ liệu cũ vì không biết đã từng thử convert
-- hay chưa.
ALTER TABLE documents ADD COLUMN viewer_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';

UPDATE documents SET viewer_status = 'READY' WHERE viewer_file_path IS NOT NULL;
UPDATE documents SET viewer_status = 'UNSUPPORTED' WHERE viewer_file_path IS NULL AND file_type NOT IN ('PDF', 'DOCX');
