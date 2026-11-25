package com.cloudinfra.notification.service;

import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.mapper.NotificationDispatchMapper;
import com.cloudinfra.notification.mapper.NotificationLogMapper;
import com.cloudinfra.notification.service.channel.NotificationChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * 通知渠道分发器 (issue 27 决策 2)
 *
 * <p>把原先内嵌在 {@code NotificationServiceImpl} 的分发逻辑独立出来, 供两个入口复用:
 * <ul>
 *   <li>{@code NotificationDispatchConsumer} — MQ 消费端 (快路径)</li>
 *   <li>{@code NotificationDispatchRecoveryTask} — 定时补扫 (崩溃恢复路径)</li>
 * </ul>
 *
 * <p><b>分发失败不抛异常</b>: 渠道投递失败已把行退回 {@code PENDING},
 * 由 {@code NotificationDispatchRecoveryTask} 按时间间隔重投 (达到上限置
 * {@code FAILED})。此处只需把结果如实落到 {@code dispatch_status} 与
 * {@code sys_notification_log}, 便于观测与人工介入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    /** 补扫重试上限。超过后置 FAILED, 不再自动重投, 转人工介入。 */
    public static final int MAX_ATTEMPTS = 3;

    private final NotificationDispatchMapper dispatchMapper;
    private final NotificationLogMapper logMapper;
    private final NotificationRouter notificationRouter;
    private final ExecutorService dispatchExecutor;

    /**
     * 分发一条通知到所有目标渠道, 并落库分发结果。
     *
     * <p>调用方需已通过 {@code claimDispatch} 完成 CAS 抢占, 本方法不重复抢占。</p>
     */
    public void dispatch(Long notificationId) {
        Notification notification = dispatchMapper.findById(notificationId);
        if (notification == null) {
            log.warn("[Dispatch] 通知不存在, 跳过: id={}", notificationId);
            return;
        }

        List<NotificationChannel> channels = resolveChannels(notification);
        if (channels.isEmpty()) {
            log.info("[Dispatch] 无可用渠道, 直接标记已分发: id={}", notificationId);
            dispatchMapper.markDispatched(notificationId);
            return;
        }

        log.info("[Dispatch] 开始分发: id={}, channels={}", notificationId,
            channels.stream().map(NotificationChannel::getChannelCode).collect(Collectors.toList()));

        List<CompletableFuture<Boolean>> futures = channels.stream()
            .map(channel -> CompletableFuture.supplyAsync(
                () -> sendToChannel(notification, channel), dispatchExecutor))
            .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .join();

        boolean anySuccess = futures.stream()
            .map(CompletableFuture::join)
            .anyMatch(Boolean::booleanValue);

        persistOutcome(notification, anySuccess);
    }

    /**
     * 依据通知记录还原目标渠道。
     *
     * <p>渠道路由需要原始的 {@code NotificationSendRequest}; 分发在 MQ 消费端异步执行,
     * 补扫重投时更早已不可得, 故用通知记录中的字段重建一个等价请求。</p>
     *
     * <p><b>渠道必须取自 {@code channels} 列 (2026-09-30 修复)</b>: 原实现恒传 {@code null},
     * 而 {@code NotificationRouter} 在渠道为空时会回落到"所有可用渠道" ——
     * 于是调用方明确要求 {@code channels=["email"]} 时, 用户会额外收到短信与
     * WebSocket 推送 (实测一次通知落 3 条 SUCCESS 日志), 属过度发送。</p>
     *
     * <p>{@code channels} 为空表示该通知写于本列加入之前 (历史数据), 此时保持原有的
     * 按偏好路由行为, 不做改变。</p>
     */
    private List<NotificationChannel> resolveChannels(Notification notification) {
        List<String> requested = decodeChannels(notification.getChannels());
        NotificationSendRequest request = new NotificationSendRequest(
            notification.getUserId(),
            notification.getTitle(),
            notification.getContent(),
            notification.getType(),
            requested,  // 还原调用方请求的渠道; 为空则交由偏好路由决定
            null,   // 模板已在入库前渲染完成
            Map.of(),
            null    // 不再调度
        );
        return notificationRouter.route(request);
    }

    private List<String> decodeChannels(String stored) {
        if (stored == null || stored.isBlank()) {
            // 未指定渠道时返回空集合而非 null: NotificationRouter 的判定是
            // `channels() != null && !channels().isEmpty()`, 两者走同一条 else 分支
            // (交由偏好路由决定), 因此改成空集合不改变行为, 且省掉每层调用方的 null 防御。
            return List.of();
        }
        return Arrays.stream(stored.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    private boolean sendToChannel(Notification notification, NotificationChannel channel) {
        String code = channel.getChannelCode();
        long startTime = System.currentTimeMillis();
        try {
            channel.send(notification.getUserId(), notification.getTitle(), notification.getContent());
            saveLog(notification.getId(), code, "SUCCESS", null, System.currentTimeMillis() - startTime);
            log.info("[Dispatch] 渠道 {} 成功 -> 通知 {} 用户 {}", code, notification.getId(),
                notification.getUserId());
            return true;
        } catch (RuntimeException channelFailure) {
            // 不重抛: MQ 侧已有重试与 DLQ, 此处只如实记录
            saveLog(notification.getId(), code, "FAIL", channelFailure.getMessage(),
                System.currentTimeMillis() - startTime);
            log.error("[Dispatch] 渠道 {} 失败 -> 通知 {}: {}", code, notification.getId(),
                channelFailure.getMessage());
            return false;
        }
    }

    /**
     * 落库分发结果。
     *
     * <p>部分渠道成功即视为分发完成 —— 用户已收到通知, 剩余渠道失败不应导致整条重投
     * (否则会向已成功的渠道重复投递)。全部失败则保持可重投状态。</p>
     */
    private void persistOutcome(Notification notification, boolean anySuccess) {
        Long id = notification.getId();
        if (anySuccess) {
            dispatchMapper.markDispatched(id);
            updateReadStatus(notification, NotificationStatus.SENT);
            log.info("[Dispatch] 分发完成: id={}", id);
        } else {
            // 全部渠道失败: 退回 PENDING 交由补扫任务重投
            dispatchMapper.releaseDispatchClaim(id);
            log.warn("[Dispatch] 全渠道失败, 退回待分发等待重试: id={}", id);
        }
    }

    private void updateReadStatus(Notification notification, NotificationStatus newStatus) {
        if (notification.getStatus() == newStatus) {
            return;
        }
        Notification update = new Notification();
        update.setId(notification.getId());
        update.setStatus(newStatus);
        dispatchMapper.updateById(update);
    }

    private void saveLog(Long notificationId, String channelCode, String status,
                         String error, long duration) {
        NotificationLog entry = new NotificationLog();
        entry.setNotificationId(notificationId);
        entry.setChannelCode(channelCode);
        entry.setStatus(status);
        entry.setErrorMessage(error);
        entry.setDuration(duration);
        logMapper.insert(entry);
    }

    /**
     * 判定一条通知是否已达到重试上限。
     */
    public boolean exhausted(Notification notification) {
        Integer attempts = notification.getDispatchAttempts();
        return attempts != null && attempts >= MAX_ATTEMPTS;
    }
}
