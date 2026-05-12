-- 通知表
CREATE TABLE IF NOT EXISTS sys_notification (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    status SMALLINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_time TIMESTAMP,
    CONSTRAINT idx_notification_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id)
);

-- 索引：用户+状态，用于快速查询未读通知
CREATE INDEX IF NOT EXISTS idx_notification_user_status ON sys_notification(user_id, status);
