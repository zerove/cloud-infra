package com.cloudinfra.notification.domain.vo;

import java.time.LocalDateTime;

/**
 * 通知记录响应 VO
 */
public record NotificationVO(
    Long notificationId,
    String title,
    String content,
    String status,
    String channel,
    LocalDateTime sendTime,
    String errorMessage
) {}
