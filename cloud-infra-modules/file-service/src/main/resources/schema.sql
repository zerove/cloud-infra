CREATE TABLE IF NOT EXISTS file_permission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_id BIGINT NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    permission_level VARCHAR(32) NOT NULL,
    INDEX idx_file_id (file_id),
    INDEX idx_subject (subject_type, subject_id)
);