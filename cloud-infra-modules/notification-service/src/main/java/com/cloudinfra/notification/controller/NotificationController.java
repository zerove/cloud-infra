package com.cloudinfra.notification.controller;

import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.api.notification.dto.NotificationDTO;
import com.cloudinfra.api.notification.dto.NotificationHistoryResponse;
import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.api.notification.dto.NotificationRequest;
import com.cloudinfra.api.notification.dto.NotificationResponse;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.domain.PageQuery;
import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.util.SecurityUtils;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.domain.UserNotificationPreference;
import com.cloudinfra.notification.domain.dto.NotificationPreferenceRequest;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import java.util.List;
import java.util.stream.Collectors;
import com.cloudinfra.notification.service.INotificationService;
import com.cloudinfra.notification.service.NotificationQuotaService;
import com.cloudinfra.notification.service.UserNotificationPreferenceService;
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
public class NotificationController {

    private final INotificationService notificationService;
    private final NotificationQuotaService quotaService;
    private final UserNotificationPreferenceService preferenceService;

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

    /**
     * 查询通知历史。
     *
     * @deprecated S2-B3 已实施：仅允许本人（{@code userId == currentUser}），跨用户 403；
     *     自助查询请使用 {@link #getMyHistory}。保留旧形状以兼容存量调用方。
     */
    @Override
    @Deprecated
    @Operation(summary = "查询通知历史（deprecated：仅本人；请使用 /notification/my/history）")
    @PreAuthorize("@ss.hasPermi('notification:history')")
    @GetMapping("/history")
    public Result<PageResult<NotificationHistoryResponse>> getHistory(
            @RequestParam Long userId,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        // S2-B3: 旧接口禁止跨用户查询；自助查询请使用 /notification/my/history
        Long current = SecurityUtils.getUserId();
        if (current == null || !current.equals(userId)) {
            return Result.fail(403, "禁止查询他人通知历史，请使用 /notification/my/history");
        }

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

    @Operation(summary = "将通知标记为已读")
    @PreAuthorize("@ss.hasPermi('notification:read')")
    @PutMapping("/read/{id}")
    public Result<Void> markAsRead(@PathVariable("id") Long id) {
        try {
            // 当前用户取自验签后的安全上下文 (HeaderAuthenticationFilter 注入),
            // 绝不接受客户端参数 —— 否则可标记他人通知
            notificationService.markAsRead(id, SecurityUtils.getUserId());
            return Result.success();
        } catch (BusinessException e) {
            // service 的 BusinessException 经 GlobalExceptionHandler 会恒按 500 处理,
            // 权限/不存在这类语义失败不应被读成"服务故障", 故显式映射 (FileController.remove 同规)。
            // 按 ErrorCode 枚举比对: 裸数字 1005/1006 一旦调整会静默失配退化为 500。
            if (ErrorCode.NO_PERMISSION.getCode().equals(e.getCode())) {
                return Result.fail(403, e.getMessage());
            }
            if (ErrorCode.RESOURCE_NOT_FOUND.getCode().equals(e.getCode())) {
                return Result.fail(404, e.getMessage());
            }
            // 未预期错误码不在此吞掉, 交全局处理器
            throw e;
        }
    }

    @Operation(summary = "根据关键词搜索通知")
    @PreAuthorize("@ss.hasPermi('notification:search')")
    @GetMapping("/search")
    public Result<PageResult<NotificationDTO>> searchNotifications(
            @RequestParam String keyword,
            PageQuery pageQuery) {
        return Result.success(notificationService.searchNotifications(keyword, pageQuery).map(this::mapToDTO));
    }

    /**
     * 获取特定用户的发送审计日志。
     *
     * @deprecated S2-B3 已实施：仅允许本人（{@code userId == currentUser}），跨用户 403；
     *     自助查询请使用 {@link #getMyLogs}。可信审计聚合请走内部 HMAC 契约。
     */
    @Override
    @Deprecated
    @Operation(summary = "获取用户发送审计日志（deprecated：仅本人；请使用 /notification/my/logs）")
    @PreAuthorize("@ss.hasPermi('notification:logs')")
    @GetMapping("/logs")
    public Result<List<NotificationLogDTO>> getLogs(@RequestParam Long userId) {
        // S2-B3: 旧接口禁止跨用户查询；自助查询请使用 /notification/my/logs
        Long current = SecurityUtils.getUserId();
        if (current == null || !current.equals(userId)) {
            return Result.fail(403, "禁止查询他人发送审计日志，请使用 /notification/my/logs");
        }
        List<NotificationLog> logs = notificationService.getLogs(userId);
        return Result.success(logs.stream()
                .map(this::mapToLogDTO)
                .collect(Collectors.toList()));
    }

    @Override
    @Operation(summary = "查询当前用户通知历史")
    @PreAuthorize("@ss.hasPermi('notification:history')")
    @GetMapping("/my/history")
    public Result<List<NotificationHistoryResponse>> getMyHistory(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        Long userId = SecurityUtils.getUserId();
        return getHistory(userId, status, type, page, size);
    }

    @Override
    @Operation(summary = "查询当前用户发送审计日志")
    @PreAuthorize("@ss.hasPermi('notification:logs')")
    @GetMapping("/my/logs")
    public Result<List<NotificationLogDTO>> getMyLogs() {
        Long userId = SecurityUtils.getUserId();
        return getLogs(userId);
    }

    @Override
    @PreAuthorize("denyAll()")
    public Result<List<NotificationLogDTO>> getInternalLogs(Long userId) {
        // 实际处理由 InternalNotificationController（/internal/**，HMAC 保护）承担。
        // 此处仅满足 NotificationApi 契约；denyAll 防止接口注解继承导致的意外暴露。
        return Result.fail(403, "请通过内部契约访问");
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

    @Operation(summary = "查询当日通知发送配额")
    @PreAuthorize("@ss.hasPermi('notification:send')")
    @GetMapping("/quota")
    public Result<NotificationQuotaStatusDTO> getQuota() {
        var s = quotaService.getQuotaStatus(SecurityUtils.getUserId());
        return Result.success(new NotificationQuotaStatusDTO(s.used(), s.limit(), s.remaining(), s.resetInSeconds()));
    }

    @Operation(summary = "查询当前用户通知偏好")
    @PreAuthorize("@ss.hasPermi('notification:read')")
    @GetMapping("/preferences")
    public Result<List<NotificationPreferenceDTO>> getPreferences() {
        Long userId = SecurityUtils.getUserId();
        return Result.success(toPreferenceDtos(preferenceService.listByUserId(userId)));
    }

    @Operation(summary = "保存当前用户通知偏好（按类型+渠道 upsert）")
    @PreAuthorize("@ss.hasPermi('notification:read')")
    @PutMapping("/preferences")
    public Result<List<NotificationPreferenceDTO>> savePreferences(
            @Valid @RequestBody List<NotificationPreferenceSaveRequest> requests) {
        Long userId = SecurityUtils.getUserId();
        List<NotificationPreferenceRequest> moduleRequests = requests == null ? List.of()
            : requests.stream()
                .map(r -> new NotificationPreferenceRequest(r.notificationType(), r.channelCode(), r.enabled()))
                .collect(Collectors.toList());
        return Result.success(toPreferenceDtos(preferenceService.saveForUser(userId, moduleRequests)));
    }

    private List<NotificationPreferenceDTO> toPreferenceDtos(List<UserNotificationPreference> prefs) {
        if (prefs == null || prefs.isEmpty()) {
            return List.of();
        }
        return prefs.stream()
            .map(p -> new NotificationPreferenceDTO(p.getNotificationType(), p.getChannelCode(), p.getEnabled()))
            .collect(Collectors.toList());
    }

    @Operation(summary = "查询当前用户通知历史（兼容安全路径，不接受 userId 参数）")
    @PreAuthorize("@ss.hasPermi('notification:history')")
    @GetMapping("/my/history")
    public Result<PageResult<NotificationHistoryResponse>> getMyHistory(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            PageQuery pageQuery) {
        Long userId = SecurityUtils.getUserId();
        PageResult<Notification> notifications =
                notificationService.getNotificationHistory(userId, status, type, pageQuery);
        return Result.success(notifications.map(n -> new NotificationHistoryResponse(
                n.getId(), n.getType(), n.getTitle(), n.getContent(), n.getStatus(), n.getCreateTime())));
    }

    @Operation(summary = "查询当前用户发送审计日志（兼容安全路径，不接受 userId 参数）")
    @PreAuthorize("@ss.hasPermi('notification:logs')")
    @GetMapping("/my/logs")
    public Result<PageResult<NotificationLogDTO>> getMyLogs(PageQuery pageQuery) {
        Long userId = SecurityUtils.getUserId();
        return Result.success(notificationService.getLogs(userId, pageQuery).map(this::mapToLogDTO));
    }

}
