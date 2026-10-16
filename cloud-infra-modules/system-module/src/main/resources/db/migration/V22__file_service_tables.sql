-- 将 file-service 的 4 张表纳入 Flyway, 使 system-module 成为全库**唯一** DDL 所有者。
--
-- 背景: 此前 file-service 用 spring.sql.init + classpath:schema.sql 建表,
-- 而 system-module 用 Flyway, 两套机制并存于同一个库。其中 file_metadata
-- 更特殊 —— Flyway 的 V14 会对它 ADD COLUMN, 但 V14 整段被
-- `IF EXISTS (SELECT 1 FROM information_schema.tables ...)` 包裹,
-- 即表不存在时**跳过**, 于是它的建表权实际落在 schema.sql 手里。
--
-- 全部使用 CREATE TABLE IF NOT EXISTS:
--   · 存量库 —— 表已由 schema.sql 建好, 本迁移为空操作
--   · 全新库 —— V14 跳过 file_metadata, 由本迁移补齐
--
-- file_metadata 必须自带 tenant_id: 全新库上 V14 已跳过, 不会再补该列。
--
-- 幂等性: 本文件可重复执行; 不含 DROP / TRUNCATE / 数据变更。

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