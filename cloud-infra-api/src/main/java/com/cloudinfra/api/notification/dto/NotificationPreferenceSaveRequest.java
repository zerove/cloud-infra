package com.cloudinfra.api.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 用户通知偏好保存项（当前用户作用域）。
 */
public record NotificationPreferenceSaveRequest(
    @NotBlank(message = "通知类型不能为空") String notificationType,
    @NotBlank(message = "渠道编码不能为空") String channelCode,
    @NotNull(message = "启用状态不能为空") Boolean enabled
) {
}
