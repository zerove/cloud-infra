package com.cloudinfra.notification.controller;

import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.api.notification.dto.NotificationDTO;
import com.cloudinfra.api.notification.dto.NotificationHistoryResponse;
import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.api.notification.dto.NotificationRequest;
import com.cloudinfra.api.notification.dto.NotificationResponse;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.domain.PageQuery;
import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.common.core.exception.ErrorCode;
import com.cloudinfra.common.util.SecurityUtils;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.service.INotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "通知管理")
@RestController
@RequestMapping("/notification")
@RequiredArgsConstructor
public class NotificationController implements NotificationApi {

    private final INotificationService notificationService;

    @Override
    @Operation(summary = "发送通知")
    @PreAuthorize("@ss.hasPermi('notification:send')")
    @PostMapping("/send")
    public Result<NotificationResponse> send(@Valid @RequestBody NotificationRequest request) {
        NotificationSendRequest sendRequest = new NotificationSendRequest(
                request.userId(),
                request.title(),
                request.content(),
                request.type(),
                request.channels(),        // 目标渠道
                request.templateCode(),    // 模版编码
                request.params(),          // 模版参数
                request.scheduleTime()     // 定时发送时间
        );
        notificationService.sendNotification(sendRequest);
        return Result.success(new NotificationResponse(null, "SENT"));
    }

    @Override
    @Operation(summary = "查询通知历史")
    @PreAuthorize("@ss.hasPermi('notification:history')")
    @GetMapping("/history")
    public Result<PageResult<NotificationHistoryResponse>> getHistory(
            @RequestParam Long userId,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            PageQuery pageQuery) {
        // 分页窗口的缺省/钳制统一由 PageQuery 处理, 控制器不再内联 Math.min/max
        PageResult<Notification> notifications =
                notificationService.getNotificationHistory(userId, status, type, pageQuery);
        return Result.success(notifications.map(n -> new NotificationHistoryResponse(
                n.getId(), n.getType(), n.getTitle(), n.getContent(), n.getStatus(), n.getCreateTime())));
    }

    @Override
    @Operation(summary = "将通知标记为已读")
    @PreAuthorize("@ss.hasPermi('notification:read')")
    @PutMapping("/read/{id}")
    public Result<Void> markAsRead(@PathVariable("id") Long id) {
        try {
            // 当前用户取自验签后的安全上下文 (HeaderAuthenticationFilter 注入),
            // 绝不接受客户端参数 —— 否则可标记他人通知
            notificationService.markAsRead(id, SecurityUtils.getUserId());
            return Result.success();
        } catch (BusinessException e) {
            // service 的 BusinessException 经 GlobalExceptionHandler 会恒按 500 处理,
            // 权限/不存在这类语义失败不应被读成"服务故障", 故显式映射 (FileController.remove 同规)。
            // 按 ErrorCode 枚举比对: 裸数字 1005/1006 一旦调整会静默失配退化为 500。
            if (ErrorCode.NO_PERMISSION.getCode().equals(e.getCode())) {
                return Result.fail(403, e.getMessage());
            }
            if (ErrorCode.RESOURCE_NOT_FOUND.getCode().equals(e.getCode())) {
                return Result.fail(404, e.getMessage());
            }
            // 未预期错误码不在此吞掉, 交全局处理器
            throw e;
        }
    }

    @Override
    @Operation(summary = "根据关键词搜索通知")
    @PreAuthorize("@ss.hasPermi('notification:search')")
    @GetMapping("/search")
    public Result<PageResult<NotificationDTO>> searchNotifications(
            @RequestParam String keyword,
            PageQuery pageQuery) {
        return Result.success(notificationService.searchNotifications(keyword, pageQuery).map(this::mapToDTO));
    }

    @Override
    @Operation(summary = "获取特定用户的发送审计日志")
    @PreAuthorize("@ss.hasPermi('notification:logs')")
    @GetMapping("/logs")
    public Result<PageResult<NotificationLogDTO>> getLogs(
            @RequestParam Long userId,
            PageQuery pageQuery) {
        return Result.success(notificationService.getLogs(userId, pageQuery).map(this::mapToLogDTO));
    }

    private NotificationDTO mapToDTO(Notification n) {
        return new NotificationDTO(
                n.getId(),
                n.getUserId(),
                n.getType(),
                n.getTitle(),
                n.getContent(),
                n.getStatus(),
                n.getCreateTime(),
                n.getReadTime()
        );
    }

    private NotificationLogDTO mapToLogDTO(NotificationLog l) {
        var dto = new NotificationLogDTO();
        dto.setNotificationId(l.getNotificationId());
        dto.setChannelCode(l.getChannelCode());
        dto.setStatus(l.getStatus());
        dto.setErrorMessage(l.getErrorMessage());
        dto.setCreateTime(l.getCreateTime());
        return dto;
    }
}

