-- V12: 补 notification 用户偏好表 (UserNotificationPreference)
--
-- 背景:
-- UserNotificationPreference 实体 (cloud-infra-modules/notification-service) 字段:
-- id, user_id, notificationType, channelCode, enabled, createTime, updateTime
-- MyBatis-Plus 自动驼峰转下划线: notification_type, channel_code, create_time, update_time
-- 之前 send 报 "column notification_type does not exist" -> 表从未建
--
-- 修复:
-- 补 sys_notification_preference 表, 字段与实体一致

CREATE TABLE IF NOT EXISTS sys_notification_preference (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    notification_type   VARCHAR(50)  NOT NULL,
    channel_code        VARCHAR(50)  NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sys_notification_preference_user FOREIGN KEY (user_id)
        REFERENCES sys_user (user_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_sys_notification_preference_user ON sys_notification_preference (user_id);

-- 默认全用户开启 inApp 渠道
INSERT INTO sys_notification_preference (user_id, notification_type, channel_code, enabled)
SELECT u.user_id, 'SYSTEM', 'inApp', TRUE
FROM sys_user u
ON CONFLICT DO NOTHING;

COMMENT ON TABLE sys_notification_preference IS '通知用户偏好 (user, type, channel enabled) - 2026-08-29 V12';

-- 推进序列
SELECT setval(pg_get_serial_sequence('sys_notification_preference', 'id'),
              (SELECT COALESCE(MAX(id), 1) FROM sys_notification_preference));
