package com.cloudinfra.notification.domain;

import com.baomidou.mybatisplus.annotation.*;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 通知实体类
 * 存储系统发送给用户的通知信息
 */
@Data
@TableName("sys_notification")
public class Notification {
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 通知类型 (使用 NotificationType 枚举)
     */
    private NotificationType type;

    /**
     * 通知标题
     */
    private String title;

    /**
     * 通知内容
     */
    private String content;

    /**
     * 通知状态 (使用 NotificationStatus 枚举: 0-未读, 1-已读)
     */
    private NotificationStatus status;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 阅读时间
     */
    private LocalDateTime readTime;
}
