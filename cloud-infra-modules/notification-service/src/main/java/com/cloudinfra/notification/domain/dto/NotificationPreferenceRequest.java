package com.cloudinfra.notification.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 当前用户通知偏好设置项（按通知类型 + 渠道维度）。
 */
public record NotificationPreferenceRequest(
    @NotBlank(message = "通知类型不能为空") String notificationType,
    @NotBlank(message = "渠道编码不能为空") String channelCode,
    @NotNull(message = "启用状态不能为空") Boolean enabled
) {
}
