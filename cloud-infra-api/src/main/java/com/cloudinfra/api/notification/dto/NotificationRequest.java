package com.cloudinfra.api.notification.dto;

import com.cloudinfra.api.notification.enums.NotificationType;

/**
 * 发送通知请求
 */
public record NotificationRequest(
    Long userId,
    NotificationType type,
    String title,
    String content,
    String target
) {}
