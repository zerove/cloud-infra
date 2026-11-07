package com.cloudinfra.api.notification;

import com.cloudinfra.api.notification.dto.NotificationDTO;
import com.cloudinfra.api.notification.dto.NotificationHistoryResponse;
import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.api.notification.dto.NotificationPreferenceDTO;
import com.cloudinfra.api.notification.dto.NotificationPreferenceSaveRequest;
import com.cloudinfra.api.notification.dto.NotificationQuotaStatusDTO;
import com.cloudinfra.api.notification.dto.NotificationRequest;
import com.cloudinfra.api.notification.dto.NotificationResponse;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.api.notification.fallback.NotificationApiFallbackFactory;
import com.cloudinfra.common.core.domain.PageQuery;
import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.common.core.domain.Result;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
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
    Result<PageResult<NotificationHistoryResponse>> getHistory(
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
    Result<PageResult<NotificationLogDTO>> getLogs(
            @RequestParam Long userId,
            @SpringQueryMap PageQuery pageQuery);

    /**
     * 查询当前用户通知历史（不接受 userId，兼容安全路径）
     */
    @GetMapping("/notification/my/history")
    @CircuitBreaker(name = "notificationService")
    @Retry(name = "notificationService")
    Result<PageResult<NotificationHistoryResponse>> getMyHistory(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            @SpringQueryMap PageQuery pageQuery);

    /**
     * 查询当前用户发送审计日志（不接受 userId）
     */
    @GetMapping("/notification/my/logs")
    @CircuitBreaker(name = "notificationService")
    @Retry(name = "notificationService")
    Result<PageResult<NotificationLogDTO>> getMyLogs(@SpringQueryMap PageQuery pageQuery);

    /**
     * 查询当日通知发送配额（当前用户）
     */
    @GetMapping("/notification/quota")
    @CircuitBreaker(name = "notificationService")
    @Retry(name = "notificationService")
    Result<NotificationQuotaStatusDTO> getQuota();

    /**
     * 查询当前用户通知偏好
     */
    @GetMapping("/notification/preferences")
    @CircuitBreaker(name = "notificationService")
    @Retry(name = "notificationService")
    Result<List<NotificationPreferenceDTO>> getPreferences();

    /**
     * 保存当前用户通知偏好
     */
    @PutMapping("/notification/preferences")
    @CircuitBreaker(name = "notificationService")
    Result<List<NotificationPreferenceDTO>> savePreferences(@RequestBody List<NotificationPreferenceSaveRequest> requests);
}