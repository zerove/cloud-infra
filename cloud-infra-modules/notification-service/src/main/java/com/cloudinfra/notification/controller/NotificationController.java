package com.cloudinfra.notification.controller;

import com.cloudinfra.api.notification.dto.*;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.service.INotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 通知管理控制器，负责处理通知发送请求、历史查询及已读标记等 API 接口。
 */
@Tag(name = "通知管理")
@RestController
@RequestMapping("/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final StreamBridge streamBridge;
    private final INotificationService notificationService;

    @Operation(summary = "发送通知 (异步)")
    @PostMapping("/send")
    public Result<NotificationResponse> send(@RequestBody NotificationRequest request) {
        // 投递至 MQ
        streamBridge.send("notificationConsumer-out-0", request);
        
        NotificationResponse response = new NotificationResponse();
        response.setStatus("ASYNC_SENT");
        return Result.success(response);
    }

    @Operation(summary = "查询通知历史")
    @GetMapping("/history")
    public Result<List<NotificationHistoryResponse>> getHistory(
            @RequestParam Long userId,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        List<Notification> history = notificationService.getHistory(userId, status, type);
        
        List<NotificationHistoryResponse> responses = history.stream().map(n -> {
            NotificationHistoryResponse r = new NotificationHistoryResponse();
            r.setId(n.getId());
            r.setType(n.getType());
            r.setTitle(n.getTitle());
            r.setContent(n.getContent());
            r.setStatus(n.getStatus());
            r.setCreateTime(n.getCreateTime());
            return r;
        }).collect(Collectors.toList());
        
        return Result.success(responses);
    }

    @Operation(summary = "标记为已读")
    @PutMapping("/read/{id}")
    public Result<Void> markAsRead(@PathVariable Long id) {
        notificationService.markAsRead(id);
        return Result.success();
    }
}
