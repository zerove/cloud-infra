CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(128) NOT NULL,
    email VARCHAR(128),
    phone VARCHAR(20),
    status INT DEFAULT 1,
    deleted INT DEFAULT 0,
    create_time DATETIME,
    update_time DATETIME
);
