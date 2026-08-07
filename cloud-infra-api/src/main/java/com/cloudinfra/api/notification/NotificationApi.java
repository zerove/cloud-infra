package com.cloudinfra.api.notification;

import com.cloudinfra.api.notification.dto.*;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.domain.Result;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@FeignClient(name = "notification-service")
public interface NotificationApi {
    /**
     * 发送通知
     */
    @PostMapping("/notification/send")
    @CircuitBreaker(name = "notificationService")
    @Retry(name = "notificationService")
    @RateLimiter(name = "notificationService")
    Result<NotificationResponse> send(@RequestBody NotificationRequest request);

    /**
     * 获取通知历史记录
     */
    @GetMapping("/notification/history")
    @CircuitBreaker(name = "notificationService")
    @Retry(name = "notificationService")
    Result<List<NotificationHistoryResponse>> getHistory(
            @RequestParam Long userId,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size);

    /**
     * 将通知标记为已读
     */
    @PutMapping("/notification/read/{id}")
    @CircuitBreaker(name = "notificationService")
    Result<Void> markAsRead(@PathVariable("id") Long id);

    /**
     * 根据关键词搜索通知
     */
    @GetMapping("/notification/search")
    Result<List<NotificationDTO>> searchNotifications(@RequestParam String keyword);

    /**
     * 获取特定用户的发送审计日志
     */
    @GetMapping("/notification/logs")
    Result<List<NotificationLogDTO>> getLogs(@RequestParam Long userId);
}