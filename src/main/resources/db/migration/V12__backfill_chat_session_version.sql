-- ChatSession.version (@Version, optimistic locking) được ddl-auto thêm vào
-- SAU khi các session cũ đã tồn tại -> ddl-auto chỉ ADD COLUMN nullable,
-- KHÔNG backfill dữ liệu cũ -> các row cũ có version = NULL.
--
-- Hibernate sinh câu lệnh xoá/sửa cho entity có @Version dạng:
--   DELETE FROM chat_sessions WHERE id = ? AND version = ?
-- Khi version trong bộ nhớ là NULL, tham số bind xuống là SQL NULL, mà
-- "version = NULL" không bao giờ TRUE (kể cả khi cột DB cũng đang NULL) ->
-- 0 dòng bị ảnh hưởng -> Hibernate coi là xung đột optimistic lock và ném
-- ObjectOptimisticLockingFailureException. Vì đây là NULL cố định (không
-- phải do người khác vừa sửa), lỗi này lặp lại ở MỌI lần thử, kể cả sau khi
-- retry -- đây chính là lý do các "cuộc trò chuyện cũ" (tạo trước khi cột
-- version tồn tại) không bao giờ xoá được, còn cuộc trò chuyện mới thì bình
-- thường.
--
-- Backfill 0 cho các row cũ rồi khoá NOT NULL DEFAULT 0 để không tái diễn.
UPDATE chat_sessions SET version = 0 WHERE version IS NULL;
ALTER TABLE chat_sessions MODIFY COLUMN version INT NOT NULL DEFAULT 0;
