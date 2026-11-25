package com.cloudinfra.api.notification.dto;

/**
 * 通知发送配额状态（当日）。
 */
public record NotificationQuotaStatusDTO(
    long used,
    long limit,
    long remaining,
    long resetInSeconds
) {
}
