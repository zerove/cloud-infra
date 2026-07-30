package com.cloudinfra.api.notification.dto;

import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import java.time.LocalDateTime;

/**
 * 通知详情 DTO
 */
public record NotificationDTO(
    Long id,
    Long userId,
    NotificationType type,
    String title,
    String content,
    NotificationStatus status,
    LocalDateTime createTime,
    LocalDateTime readTime
) {}
