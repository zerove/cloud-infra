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
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private NotificationType type;
    private String title;
    private String content;
    private NotificationStatus status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    private LocalDateTime readTime;

    /**
     * 创建新通知 (领域工厂方法)
     */
    public static Notification create(Long userId, NotificationType type, String title, String content) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setStatus(NotificationStatus.UNREAD);
        return n;
    }

    /**
     * 标记为已读 (领域行为)
     */
    public void markAsRead() {
        this.status = NotificationStatus.READ;
        this.readTime = LocalDateTime.now();
    }
}

