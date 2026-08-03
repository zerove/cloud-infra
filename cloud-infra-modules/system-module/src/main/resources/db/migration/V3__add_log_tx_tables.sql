-- Flyway 迁移脚本 (PostgreSQL 16)
-- 版本: V3__add_log_tx_tables.sql
-- 描述: 操作日志、登录日志、本地事务消息表

-- ========== 操作日志表 ==========
CREATE TABLE IF NOT EXISTS sys_oper_log (
    oper_id        BIGSERIAL PRIMARY KEY,
    user_id        BIGINT,
    title          VARCHAR(50) DEFAULT '',
    business_type  INT DEFAULT 0,
    method         VARCHAR(100) DEFAULT '',
    request_method VARCHAR(10) DEFAULT '',
    operator_type  INT DEFAULT 0,
    oper_name      VARCHAR(50) DEFAULT '',
    dept_name      VARCHAR(50) DEFAULT '',
    oper_url       VARCHAR(255) DEFAULT '',
    oper_ip        VARCHAR(128) DEFAULT '',
    oper_location  VARCHAR(255) DEFAULT '',
    oper_param     TEXT DEFAULT '',
    json_result    TEXT DEFAULT '',
    status         INT DEFAULT 0,
    error_msg      TEXT DEFAULT '',
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    cost_time      BIGINT DEFAULT 0
);

COMMENT ON TABLE sys_oper_log IS '操作日志记录';

-- ========== 系统访问记录表 ==========
CREATE TABLE IF NOT EXISTS sys_login_log (
    info_id        BIGSERIAL PRIMARY KEY,
    user_name      VARCHAR(50) DEFAULT '',
    ipaddr         VARCHAR(128) DEFAULT '',
    login_location VARCHAR(255) DEFAULT '',
    browser        VARCHAR(50) DEFAULT '',
    os             VARCHAR(50) DEFAULT '',
    status         CHAR(1) DEFAULT '0',
    msg            VARCHAR(255) DEFAULT '',
    login_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_login_log IS '系统访问记录';

-- ========== 本地事务消息表 ==========
CREATE TABLE IF NOT EXISTS sys_tx_message (
    id          BIGSERIAL PRIMARY KEY,
    topic       VARCHAR(128) NOT NULL,
    content     TEXT NOT NULL,
    status      SMALLINT DEFAULT 0,
    retry_count INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_tx_message IS '本地事务消息表';
COMMENT ON COLUMN sys_tx_message.status IS '状态 (0:待发送, 1:已发送, 2:已处理, 3:处理失败)';
