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
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

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
     * 获取通知历史记录 (分页, 可按状态/类型过滤)
     *
     * <p>分页窗口由 {@link PageQuery} 承载: 缺省 pageNum=1 / pageSize=10,
     * 越界值由其 {@code normalized*} 方法统一钳制; 返回 {@link PageResult}
     * 含 total 便于翻页。
     */
    @GetMapping("/notification/history")
    @CircuitBreaker(name = "notificationService")
    @Retry(name = "notificationService")
    Result<List<NotificationHistoryResponse>> getHistory(
            @RequestParam Long userId,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            @SpringQueryMap PageQuery pageQuery);

    /**
     * 将通知标记为已读
     */
    @PutMapping("/notification/read/{id}")
    @CircuitBreaker(name = "notificationService")
    Result<Void> markAsRead(@PathVariable("id") Long id);

    /**
     * 根据关键词搜索通知 (分页)
     *
     * <p>分页窗口由 {@link PageQuery} 承载; 返回 {@link PageResult} 含 total,
     * 调用方据此显式翻页。
     */
    @GetMapping("/notification/search")
    Result<PageResult<NotificationDTO>> searchNotifications(
            @RequestParam String keyword,
            @SpringQueryMap PageQuery pageQuery);

    /**
     * 获取特定用户的发送审计日志 (按日志行分页, records 与 total 同为日志条数)
     */
    @GetMapping("/notification/logs")
    Result<List<NotificationLogDTO>> getLogs(@RequestParam Long userId);

    /**
     * 查询当前用户通知历史（不接受 userId，兼容安全路径）
     */
    @GetMapping("/notification/my/history")
    @CircuitBreaker(name = "notificationService")
    @Retry(name = "notificationService")
    Result<List<NotificationHistoryResponse>> getMyHistory(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size);

    /**
     * 查询当前用户发送审计日志（不接受 userId，兼容安全路径）
     */
    @GetMapping("/notification/my/logs")
    @CircuitBreaker(name = "notificationService")
    @Retry(name = "notificationService")
    Result<List<NotificationLogDTO>> getMyLogs();

    /**
     * 获取指定用户发送审计日志（内部 Feign 契约，HMAC 保护，不经网关暴露）。
     * 形状与 master {@code getLogs(userId)} 对齐，供可信审计聚合使用。
     */
    @GetMapping("/internal/notification/logs")
    Result<List<NotificationLogDTO>> getInternalLogs(@RequestParam Long userId);
}