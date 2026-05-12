package com.cloudinfra.api.notification;

import com.cloudinfra.api.notification.dto.*;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 通知服务 API 接口
 */
@FeignClient(name = "notification-service")
public interface NotificationApi {
    /**
     * 发送通知
     *
     * @param request 通知请求参数
     * @return 发送结果
     */
    @PostMapping("/notification/send")
    Result<NotificationResponse> send(@RequestBody NotificationRequest request);

    /**
     * 获取通知历史记录
     *
     * @param userId 用户ID
     * @param status 阅读状态 (可选)
     * @param type 通知类型 (可选)
     * @param page 页码 (默认 1)
     * @param size 每页大小 (默认 10)
     * @return 通知历史记录列表
     */
    @GetMapping("/notification/history")
    Result<List<NotificationHistoryResponse>> getHistory(
            @RequestParam Long userId,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size);

    /**
     * 将通知标记为已读
     *
     * @param id 通知ID
     * @return 操作结果
     */
    @PutMapping("/notification/read/{id}")
    Result<Void> markAsRead(@PathVariable("id") Long id);
}
