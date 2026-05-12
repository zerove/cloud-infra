package com.cloudinfra.api.notification.dto;

import java.io.Serializable;
import lombok.Data;

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
