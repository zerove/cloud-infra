package com.cloudinfra.notification.controller;

import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.api.notification.dto.NotificationDTO;
import com.cloudinfra.api.notification.dto.NotificationHistoryResponse;
import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.api.notification.dto.NotificationRequest;
import com.cloudinfra.api.notification.dto.NotificationResponse;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.domain.Result;
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

import java.util.List;
import java.util.stream.Collectors;

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
    public Result<List<NotificationHistoryResponse>> getHistory(
            @RequestParam Long userId,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        
        // 校验分页参数（size 上限 100，防止大分页拖垮 DB）
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Notification> notifications = notificationService.getNotificationHistory(userId, safePage, safeSize);
        List<NotificationHistoryResponse> responses = notifications.stream()
                .map(n -> new NotificationHistoryResponse(
                    n.getId(), n.getType(), n.getTitle(), n.getContent(), n.getStatus(), n.getCreateTime()))
                .collect(Collectors.toList());
        return Result.success(responses);
    }

    @Override
    @Operation(summary = "将通知标记为已读")
    @PreAuthorize("@ss.hasPermi('notification:read')")
    @PutMapping("/read/{id}")
    public Result<Void> markAsRead(@PathVariable("id") Long id) {
        // 暂未在 Service 实现
        return Result.success();
    }

    @Override
    @Operation(summary = "根据关键词搜索通知")
    @PreAuthorize("@ss.hasPermi('notification:search')")
    @GetMapping("/search")
    public Result<List<NotificationDTO>> searchNotifications(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        List<Notification> notifications =
                notificationService.searchNotifications(keyword, Math.max(page, 1), Math.min(Math.max(size, 1), 100));
        return Result.success(notifications.stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList()));
    }

    @Override
    @Operation(summary = "获取特定用户的发送审计日志")
    @PreAuthorize("@ss.hasPermi('notification:logs')")
    @GetMapping("/logs")
    public Result<List<NotificationLogDTO>> getLogs(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        List<NotificationLog> logs =
                notificationService.getLogs(userId, Math.max(page, 1), Math.min(Math.max(size, 1), 100));
        return Result.success(logs.stream()
                .map(this::mapToLogDTO)
                .collect(Collectors.toList()));
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

