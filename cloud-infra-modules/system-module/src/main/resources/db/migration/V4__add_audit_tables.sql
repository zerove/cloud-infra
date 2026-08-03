-- Flyway 迁移脚本 (PostgreSQL 16)
-- 版本: V4__add_audit_tables.sql
-- 描述: 审计日志、资源映射、智能服务工单表

-- ========== 审计日志表 ==========
CREATE TABLE IF NOT EXISTS sys_audit_log (
    id          BIGSERIAL PRIMARY KEY,
    module      VARCHAR(50),
    action      VARCHAR(50),
    user_id     VARCHAR(64),
    username    VARCHAR(50),
    method_name VARCHAR(255),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_audit_log IS '业务审计日志表';

-- ========== 资源映射表 ==========
CREATE TABLE IF NOT EXISTS sys_resource_mapping (
    id            BIGSERIAL PRIMARY KEY,
    resource_id   VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    owner_id      BIGINT,
    node_type     VARCHAR(32),
    relation_type VARCHAR(32),
    create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_resource_mapping IS '资源映射表';

-- ========== 智能服务工单表 ==========
CREATE TABLE IF NOT EXISTS service_ticket (
    id           BIGSERIAL PRIMARY KEY,
    ticket_no    VARCHAR(64) NOT NULL UNIQUE,
    title        VARCHAR(200) NOT NULL,
    content      TEXT,
    priority     INT DEFAULT 2,
    status       VARCHAR(20) DEFAULT 'OPEN',
    requester_id BIGINT,
    assignee_id  BIGINT,
    asset_id     BIGINT,
    create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    resolve_time TIMESTAMP
);

COMMENT ON TABLE service_ticket IS '智能服务工单表';
COMMENT ON COLUMN service_ticket.priority IS '优先级 (1:低, 2:中, 3:高, 4:紧急)';
COMMENT ON COLUMN service_ticket.status IS '状态 (OPEN, PROCESSING, RESOLVED, CLOSED)';
