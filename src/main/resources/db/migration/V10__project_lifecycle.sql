-- Project lifecycle upgrade: ngày bắt đầu/kết thúc dự kiến, loại dự án, chi
-- phí, trạng thái 4 bước (RUNNING/PAUSED/COMPLETED/EXTENDED) thay cho cờ
-- active boolean cũ, và cơ chế gia hạn (extension_date) có thể chờ duyệt
-- (pending_extension_date) tách biệt khỏi ngày dự kiến kết thúc gốc.
ALTER TABLE projects ADD COLUMN start_date DATE NULL;
ALTER TABLE projects ADD COLUMN expected_end_date DATE NULL;
ALTER TABLE projects ADD COLUMN extension_date DATE NULL;
ALTER TABLE projects ADD COLUMN pending_extension_date DATE NULL;
ALTER TABLE projects ADD COLUMN extension_requested_by BIGINT NULL;
ALTER TABLE projects ADD COLUMN extension_requested_at DATETIME NULL;
ALTER TABLE projects ADD COLUMN project_type VARCHAR(100) NULL;
ALTER TABLE projects ADD COLUMN cost DECIMAL(18,2) NULL;
ALTER TABLE projects ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'RUNNING';

-- Backfill status từ active cũ TRƯỚC khi xoá cột active, để không mất thông
-- tin bật/tắt của các project đã tồn tại.
UPDATE projects SET status = CASE WHEN active = TRUE THEN 'RUNNING' ELSE 'PAUSED' END;
ALTER TABLE projects DROP COLUMN active;

-- Leader theo từng dự án (không phải role hệ thống) -- tối đa 1 leader mỗi
-- project, bất biến này được enforce ở tầng service (ProjectService.setLeader).
ALTER TABLE project_members ADD COLUMN is_leader BOOLEAN NOT NULL DEFAULT FALSE;

-- Cho phép notification trỏ tới 1 project (yêu cầu gia hạn chờ duyệt), song
-- song với document_id đã có sẵn ở V8.
ALTER TABLE notifications ADD COLUMN project_id BIGINT NULL;
