package com.cloudinfra.api.notification.dto;

import com.cloudinfra.api.notification.enums.NotificationType;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * 发送通知请求
 */
public record NotificationRequest(
    @NotNull(message = "用户ID不能为空") Long userId,
    @NotNull(message = "通知类型不能为空") NotificationType type,
    String title,
    String content,
    List<String> channels,
    String templateCode,
    Map<String, Object> params,
    java.time.LocalDateTime scheduleTime
) {}
