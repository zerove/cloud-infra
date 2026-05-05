-- User Table
CREATE TABLE IF NOT EXISTS `sys_user` (
    `user_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username` VARCHAR(64) NOT NULL COMMENT '登录账号',
    `password` VARCHAR(128) NOT NULL COMMENT '加密密码',
    `nickname` VARCHAR(64) COMMENT '用户昵称',
    `phone` VARCHAR(20) COMMENT '手机号',
    `email` VARCHAR(64) COMMENT '邮箱',
    `status` TINYINT DEFAULT 0 COMMENT '状态 (0:正常, 1:停用)',
    `create_by` VARCHAR(64) COMMENT '创建者',
    `create_time` DATETIME COMMENT '创建时间',
    `update_by` VARCHAR(64) COMMENT '更新者',
    `update_time` DATETIME COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除 (0:未删除, 1:删除)',
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户信息表';
