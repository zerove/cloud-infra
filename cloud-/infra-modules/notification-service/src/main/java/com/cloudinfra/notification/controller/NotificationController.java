package com.cloudinfra.notification.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.domain.vo.NotificationVO;
import com.cloudinfra.notification.service.INotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "通知管理")
@RestController
@RequestMapping("/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final INotificationService notificationService;

    @Operation(summary = "发送通知")
    @PostMapping("/send")
    public Result<Void> send(@Valid @RequestBody NotificationSendRequest request) {
        notificationService.sendNotification(request);
        return Result.success();
    }

    @Operation(summary = "查询通知历史")
    @GetMapping("/history/{userId}")
    public Result<List<NotificationVO>> history(@PathVariable Long userId) {
        return Result.success(notificationService.getNotificationHistory(userId));
    }

    @Operation(summary = "获取用户发送审计日志")
    @GetMapping("/logs")
    public Result<List<NotificationLog>> getLogs(@RequestParam Long userId) {
        return Result.success(notificationService.getLogs(userId));
    }
}