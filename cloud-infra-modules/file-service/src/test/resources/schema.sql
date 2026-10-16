CREATE TABLE IF NOT EXISTS file_metadata (
    id BIGINT PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(512) NOT NULL,
    file_type VARCHAR(64),
    file_size BIGINT,
    uploader VARCHAR(64),
    create_time TIMESTAMP,
    current_version INT DEFAULT 1,
    tenant_id VARCHAR(50) NOT NULL DEFAULT 'default'
);

CREATE INDEX IF NOT EXISTS idx_file_metadata_tenant_id ON file_metadata(tenant_id);

CREATE TABLE IF NOT EXISTS file_permission (
    id BIGINT PRIMARY KEY,
    file_id BIGINT NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    permission_level VARCHAR(32) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_file_id ON file_permission (file_id);
CREATE INDEX IF NOT EXISTS idx_subject ON file_permission (subject_type, subject_id);

CREATE TABLE IF NOT EXISTS sys_storage_quota (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    max_size_bytes BIGINT NOT NULL,
    used_size_bytes BIGINT DEFAULT 0,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);

CREATE TABLE IF NOT EXISTS file_version (
    id BIGINT PRIMARY KEY,
    file_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    create_time TIMESTAMP
);
