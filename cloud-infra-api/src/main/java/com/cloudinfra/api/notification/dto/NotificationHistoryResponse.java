package com.cloudinfra.api.notification.dto;

import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知历史响应
 */
@Data
public class NotificationHistoryResponse implements Serializable {
    /**
     * 通知ID
     */
    private Long id;
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
     * 阅读状态
     */
    private NotificationStatus status;
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
