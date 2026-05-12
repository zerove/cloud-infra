package com.cloudinfra.api.notification.dto;

import com.cloudinfra.api.notification.enums.NotificationType;
import lombok.Data;
import java.io.Serializable;

/**
 * 发送通知请求
 */
@Data
public class NotificationRequest implements Serializable {
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 通知类型
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
     * 接收目标 (例如: 邮箱或手机号)
     */
    private String target;
}
