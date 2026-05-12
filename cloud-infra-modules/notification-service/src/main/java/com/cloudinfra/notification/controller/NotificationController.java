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

    /**
     * 异步发送通知。
     * <p>
     * 将通知请求投递至消息队列，由后台消费者异步处理具体的发送逻辑（如邮件、短信），
     * 从而提高接口响应速度并实现削峰填谷。
     *
     * @param request 通知请求参数
     * @return 包含异步发送状态的结果
     */
    @Operation(summary = "发送通知 (异步)")
    @PostMapping("/send")
    public Result<NotificationResponse> send(@RequestBody NotificationRequest request) {
        // 投递至 MQ
        streamBridge.send("notification-topic", request);
        
        NotificationResponse response = new NotificationResponse();
        response.setStatus("ASYNC_SENT");
        return Result.success(response);
    }

    /**
     * 查询用户的通知历史记录。
     * <p>
     * 支持根据用户 ID、通知状态和通知类型进行过滤，并支持分页查询。
     *
     * @param userId  用户 ID
     * @param status  通知状态（可选）
     * @param type    通知类型（可选）
     * @param page    页码，默认为 1
     * @param size    每页数量，默认为 10
     * @return 用户通知历史列表
     */
    @Operation(summary = "查询通知历史")
    @GetMapping("/history")
    public Result<List<NotificationHistoryResponse>> getHistory(
            @RequestParam Long userId,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        
        List<Notification> history = notificationService.getHistory(userId, status, type, page, size);
        
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

    /**
     * 将指定 ID 的通知标记为已读。
     *
     * @param id 通知记录 ID
     * @return 操作结果
     */
    @Operation(summary = "标记为已读")
    @PutMapping("/read/{id}")
    public Result<Void> markAsRead(@PathVariable Long id) {
        notificationService.markAsRead(id);
        return Result.success();
    }
}
