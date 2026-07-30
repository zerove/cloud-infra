package com.cloudinfra.notification.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import com.cloudinfra.api.notification.enums.NotificationType;

/**
 * 发送通知请求 DTO
 */
public record NotificationSendRequest(
    @NotNull(message = "用户ID不能为空") Long userId,
    String title,
    String content,
    @NotNull(message = "通知类型不能为空") NotificationType type,
    List<String> channels, // 目标渠道，如 ["email", "sms", "websocket"]
    String templateCode,   // 模版唯一编码 (如果指定，则优先使用模版渲染内容)
    Map<String, Object> params, // 模版填充参数
    java.time.LocalDateTime scheduleTime // 定时发送时间 (如果指定，则进入调度队列)
) {
    // 为了向后兼容，保留一个验证逻辑：如果没给 templateCode，则 title 和 content 必须非空
    public void validate() {
        if (templateCode == null || templateCode.isBlank()) {
            if (title == null || title.isBlank()) throw new IllegalArgumentException("Title is required when templateCode is not provided");
            if (content == null || content.isBlank()) throw new IllegalArgumentException("Content is required when templateCode is not provided");
        }
    }
}