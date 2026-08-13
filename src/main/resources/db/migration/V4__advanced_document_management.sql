-- V4: Advanced Document Management and Retrieval
ALTER TABLE documents ADD COLUMN document_uuid VARCHAR(36) UNIQUE;
ALTER TABLE documents ADD COLUMN decision_number VARCHAR(20);
ALTER TABLE documents ADD COLUMN classification VARCHAR(50) NOT NULL DEFAULT 'OTHER';
ALTER TABLE documents ADD COLUMN internal_source_flag BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE documents ADD COLUMN description TEXT;
ALTER TABLE documents ADD COLUMN uploader_role VARCHAR(100);
ALTER TABLE documents ADD COLUMN department_name VARCHAR(100);
ALTER TABLE documents ADD COLUMN project_name VARCHAR(100);

-- Update existing records with UUIDs if needed (optional but good for consistency)
-- (uses MySQL's UUID(), not Postgres's gen_random_uuid()::text)
UPDATE documents SET document_uuid = UUID() WHERE document_uuid IS NULL;
ALTER TABLE documents MODIFY COLUMN document_uuid VARCHAR(36) NOT NULL;

-- Create table for decision number sequence
CREATE TABLE decision_number_sequences (
    prefix VARCHAR(10) PRIMARY KEY,
    last_sequence BIGINT NOT NULL DEFAULT 0
);

INSERT INTO decision_number_sequences (prefix, last_sequence) VALUES ('SME', 0);

-- Indexes for fast retrieval
CREATE INDEX idx_docs_decision_number ON documents(decision_number);
CREATE INDEX idx_docs_project_name ON documents(project_name);
CREATE INDEX idx_docs_department_name ON documents(department_name);
CREATE INDEX idx_docs_uploaded_by ON documents(uploaded_by);
CREATE INDEX idx_docs_classification ON documents(classification);
CREATE INDEX idx_docs_uuid ON documents(document_uuid);
