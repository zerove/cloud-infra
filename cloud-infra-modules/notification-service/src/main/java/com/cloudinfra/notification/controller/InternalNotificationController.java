package com.cloudinfra.notification.controller;

import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.common.core.domain.PageQuery;
import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.notification.service.INotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知内部 Feign 契约端点（HMAC 保护，网关无路由）。
 *
 * <p>S2-B3 对用户端 {@code /notification/history|logs} 强制本人作用域；
 * 跨用户审计聚合等可信服务间场景走本端点，由 {@code NotificationSecurityConfig}
 * 的 {@code /internal/**} 单点签名决策保护。</p>
 */
@Tag(name = "通知内部契约")
@RestController
@RequestMapping("/internal/notification")
@RequiredArgsConstructor
public class InternalNotificationController {

    private final INotificationService notificationService;

    @Operation(summary = "获取指定用户发送审计日志（内部 Feign 契约）")
    @GetMapping("/logs")
    public Result<PageResult<NotificationLogDTO>> getInternalLogs(
            @RequestParam Long userId,
            PageQuery pageQuery) {
        return Result.success(notificationService.getLogs(userId, pageQuery)
            .map(this::mapToLogDTO));
    }

    private NotificationLogDTO mapToLogDTO(com.cloudinfra.notification.domain.NotificationLog l) {
        var dto = new NotificationLogDTO();
        dto.setNotificationId(l.getNotificationId());
        dto.setChannelCode(l.getChannelCode());
        dto.setStatus(l.getStatus());
        dto.setErrorMessage(l.getErrorMessage());
        dto.setCreateTime(l.getCreateTime());
        return dto;
    }
}
