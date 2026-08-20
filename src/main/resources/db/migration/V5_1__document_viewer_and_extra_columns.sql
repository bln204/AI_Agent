-- Các cột này đã tồn tại trên DB đang chạy (được Hibernate ddl-auto=update
-- tự thêm ở dev, hoặc patch thủ công) nhưng chưa từng được V1-V4 ghi lại.
-- Cần thiết để DB mới dựng từ database.sql + V1-V4 khớp với những gì
-- production (ddl-auto=validate) yêu cầu. Chỉ chạy trên DB CHƯA có các cột
-- này — nếu DB đã có (vd. đã patch thủ công trước đó), bỏ qua migration này.
ALTER TABLE documents ADD COLUMN viewer_file_path VARCHAR(500);
ALTER TABLE documents ADD COLUMN decision VARCHAR(255);
ALTER TABLE documents ADD COLUMN version INT NOT NULL DEFAULT 1;
ALTER TABLE documents ADD COLUMN is_deleted TINYINT(1) NOT NULL DEFAULT 0;
