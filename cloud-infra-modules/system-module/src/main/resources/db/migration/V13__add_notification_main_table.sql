-- V13: 补 sys_notification 主表 (notification 核心表从未在 V1-V11 创建)
--
-- 背景:
-- Notification 实体 (@TableName("sys_notification")) 依赖该表, 但 Flyway V1-V11 漏建.
-- V12 已建 sys_notification_preference (UserNotificationPreference), 但主表 sys_notification 仍缺.
-- 实测: notification send 流程报 "column notification_type does not exist" -> 500 错误.
--
-- 修复:
-- 补 sys_notification 主表, 字段对齐 Notification 实体 + NotificationType/Status enum
-- type 字段 MyBatis-Plus 自动映射为 notification_type (避免 Java 关键字)
-- status 同理映射为 status 列

CREATE TABLE IF NOT EXISTS sys_notification (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    title           VARCHAR(255) NOT NULL,
    content         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'UNREAD',
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_time       TIMESTAMP,
    CONSTRAINT fk_sys_notification_user FOREIGN KEY (user_id)
        REFERENCES sys_user (user_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_sys_notification_user_create ON sys_notification (user_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_sys_notification_user_status ON sys_notification (user_id, status);

COMMENT ON TABLE sys_notification IS '通知主表 (notification core) - 2026-08-29 V13';

-- 推进序列
SELECT setval(pg_get_serial_sequence('sys_notification', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM sys_notification));
