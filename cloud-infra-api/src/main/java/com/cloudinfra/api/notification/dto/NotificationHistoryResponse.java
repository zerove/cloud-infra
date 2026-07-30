package com.cloudinfra.api.notification.dto;

import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import java.time.LocalDateTime;

/**
 * 通知历史响应
 */
public record NotificationHistoryResponse(
    Long id,
    NotificationType type,
    String title,
    String content,
    NotificationStatus status,
    LocalDateTime createTime
) {}
