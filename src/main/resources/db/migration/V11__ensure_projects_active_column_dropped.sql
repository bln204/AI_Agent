-- V10 đã DROP COLUMN active, nhưng cột này bị phát hiện xuất hiện TRỞ LẠI trên
-- ít nhất 1 môi trường (nghi vấn: một lần chạy ddl-auto=update từ bản code cũ
-- -- trước khi Project.active bị xoá khỏi entity -- nhắm vào cùng DB chia sẻ).
-- Bài học: `ALTER TABLE ... DROP COLUMN active` (V10) không tự chống tái phát
-- nếu có nguồn nào khác vô tình thêm lại cột. Migration này viết lại theo
-- kiểu tự chữa lành (idempotent, dùng dynamic SQL vì MySQL không hỗ trợ cú
-- pháp DROP COLUMN IF EXISTS -- đã verify bằng tay, báo lỗi cú pháp), an toàn
-- chạy trên mọi môi trường dù `active` có tồn tại hay không.
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'projects' AND column_name = 'active'
);
SET @sql = IF(@col_exists > 0, 'ALTER TABLE projects DROP COLUMN active', 'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
