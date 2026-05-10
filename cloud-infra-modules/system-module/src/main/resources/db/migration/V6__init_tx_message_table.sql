-- Transaction Message Table
CREATE TABLE IF NOT EXISTS `sys_tx_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `topic` VARCHAR(128) NOT NULL COMMENT '消息主题',
    `content` TEXT NOT NULL COMMENT '消息内容',
    `status` TINYINT DEFAULT 0 COMMENT '状态 (0:待发送, 1:已发送, 2:已处理, 3:处理失败)',
    `retry_count` INT DEFAULT 0 COMMENT '重试次数',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地事务消息表';
