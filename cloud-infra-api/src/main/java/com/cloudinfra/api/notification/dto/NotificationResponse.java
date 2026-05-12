package com.cloudinfra.api.notification.dto;

import com.cloudinfra.api.notification.enums.NotificationStatus;
import lombok.Data;
import java.io.Serializable;

/**
 * 发送通知响应
 */
@Data
public class NotificationResponse implements Serializable {
    /**
     * 通知ID
     */
    private Long notificationId;
    /**
     * 发送状态
     */
    private String status;
}
