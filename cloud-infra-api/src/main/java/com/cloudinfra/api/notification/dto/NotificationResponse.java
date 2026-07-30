package com.cloudinfra.api.notification.dto;

/**
 * 发送通知响应
 */
public record NotificationResponse(
    Long notificationId,
    String status
) {}
