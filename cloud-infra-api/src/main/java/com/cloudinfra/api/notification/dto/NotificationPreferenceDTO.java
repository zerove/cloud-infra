package com.cloudinfra.api.notification.dto;

/**
 * 用户通知偏好项。
 */
public record NotificationPreferenceDTO(
    String notificationType,
    String channelCode,
    Boolean enabled
) {
}
