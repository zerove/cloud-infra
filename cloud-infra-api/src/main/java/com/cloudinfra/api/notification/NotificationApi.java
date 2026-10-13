package com.cloudinfra.api.notification;

import com.cloudinfra.api.notification.dto.NotificationDTO;
import com.cloudinfra.api.notification.dto.NotificationHistoryResponse;
import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.api.notification.dto.NotificationRequest;
import com.cloudinfra.api.notification.dto.NotificationResponse;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.api.notification.fallback.NotificationApiFallbackFactory;
import com.cloudinfra.common.core.domain.Result;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;

@FeignClient(name = "notification-service", fallbackFactory = NotificationApiFallbackFactory.class)
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
     *
     * <p>分页参数为可选：不传时按默认值返回单页，调用方需显式翻页才能取全量。
     */
    @GetMapping("/notification/search")
    Result<List<NotificationDTO>> searchNotifications(
            @RequestParam String keyword,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size);

    /**
     * 获取特定用户的发送审计日志
     */
    @GetMapping("/notification/logs")
    Result<List<NotificationLogDTO>> getLogs(
            @RequestParam Long userId,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size);
}