package com.cloudinfra.notification.controller;

import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.api.notification.dto.*;
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
import org.springframework.web.bind.annotation.*;

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
    @PostMapping("/send")
    public Result<NotificationResponse> send(@Valid @RequestBody NotificationRequest request) {
        // 这里简化处理，直接返回成功
        notificationService.sendNotification(new NotificationSendRequest(
                request.userId(), request.title(), request.content(), request.type(),
                null, null, null, null));
        return Result.success(new NotificationResponse(null, "SENT"));
    }

    @Override
    @Operation(summary = "查询通知历史")
    @GetMapping("/history")
    public Result<List<NotificationHistoryResponse>> getHistory(
            @RequestParam Long userId,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        
        // 暂未实现分页过滤，直接返回所有历史
        List<Notification> notifications = notificationService.getNotificationHistory(userId);
        List<NotificationHistoryResponse> responses = notifications.stream()
                .map(n -> new NotificationHistoryResponse(
                    n.getId(), n.getType(), n.getTitle(), n.getContent(), n.getStatus(), n.getCreateTime()))
                .collect(Collectors.toList());
        return Result.success(responses);
    }

    @Override
    @Operation(summary = "将通知标记为已读")
    @PutMapping("/read/{id}")
    public Result<Void> markAsRead(@PathVariable("id") Long id) {
        // 暂未在 Service 实现
        return Result.success();
    }

    @Override
    @Operation(summary = "根据关键词搜索通知")
    @GetMapping("/search")
    public Result<List<NotificationDTO>> searchNotifications(@RequestParam String keyword) {
        List<Notification> notifications = notificationService.searchNotifications(keyword);
        return Result.success(notifications.stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList()));
    }

    @Override
    @Operation(summary = "获取特定用户的发送审计日志")
    @GetMapping("/logs")
    public Result<List<NotificationLogDTO>> getLogs(@RequestParam Long userId) {
        List<NotificationLog> logs = notificationService.getLogs(userId);
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

