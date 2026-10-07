package com.cloudinfra.notification.service;

import com.cloudinfra.api.notification.enums.DispatchStatus;
import com.cloudinfra.notification.config.RabbitMQConfig;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.mapper.NotificationDispatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通知分发补扫任务 (issue 27 决策 2)
 *
 * <p><b>解决的问题</b>: 原先通知分发在事务提交后由内存线程池执行, 存在两个真实缺陷 ——
 * 事务已提交但分发任务未执行时进程崩溃, 通知永久丢失且无痕迹; 渠道失败无重试。
 * 本任务与 {@code NotificationDispatchConsumer} 共同构成"至少一次"投递:
 * 消费者是快路径, 本任务是崩溃恢复的兜底。
 *
 * <p><b>扫描范围</b>: {@code dispatch_status ∈ (PENDING, DISPATCHING)} 且已陈旧的通知。
 * DISPATCHING 也纳入, 因为消费者在投递中崩溃会让行永久滞留在该状态。
 *
 * <p><b>多副本安全</b>: 沿用 {@code NotificationScheduler} 的 Redis SET NX EX 分布式锁,
 * 保证同一时刻仅一个实例扫描; 记录处理前用 CAS 抢占归属, 避免与在途消费者重复分发。
 *
 * <p><b>为何不引入 Outbox 框架</b>: 见 issue 27 决策 2。通知行本身已承载发件箱语义
 * (dispatch_status + dispatch_attempts), 独立的 outbox 表 + 投递器 + AOP 注解
 * 对这两个需求的增益有限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchRecoveryTask {

    /** 多副本扫描互斥锁 */
    private static final String RECOVERY_LOCK_KEY = "notification:dispatch:recovery:lock";
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    /**
     * 陈旧阈值: 通知创建后多久未完成分发即视为可能丢失。
     *
     * <p>取 2 分钟是给正常在途分发留出的缓冲 —— 消费者从 MQ 取出到 CAS 抢占通常在
     * 秒级, 2 分钟足以覆盖消费积压与 GC 停顿, 又不会让真实丢失的通知等待过久。</p>
     */
    private static final int STALE_MINUTES = 2;

    /** 单批处理上限, 防止积压时一次拉取过多拖垮实例 */
    private static final int BATCH_LIMIT = 100;

    private final NotificationDispatchMapper dispatchMapper;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;
    private final NotificationDispatcher dispatcher;
    /**
     * 异步执行器 —— 必须限定为 {@code applicationTaskExecutor}。
     *
     * <p>与 {@code NotificationServiceImpl} 同因（CI E2E run 37059177909）：
     * {@code AsyncTaskExecutor} 另有 {@code taskScheduler} 一个候选，
     * 按类型注入会报 "required a single bean, but 2 were found"。
     * 本类由 {@code @Scheduled} 驱动，在同一上下文中被创建，
     * 因此两处必须同时修 —— 只修一处仍然起不来。
     */
    @Qualifier("notificationAsyncExecutor")
    private final Executor taskExecutor;

    /**
     * 每分钟扫描一次陈旧且未完成分发的通知并重投。
     */
    @Scheduled(cron = "0 * * * * *")
    public void recoverStaleDispatches() {
        String lockValue = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
            RECOVERY_LOCK_KEY, lockValue, LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("其他实例正在执行分发补扫, 本次跳过");
            return;
        }
        try {
            scanAndRepublish();
        } finally {
            String current = redisTemplate.opsForValue().get(RECOVERY_LOCK_KEY);
            if (lockValue.equals(current)) {
                redisTemplate.delete(RECOVERY_LOCK_KEY);
            }
        }
    }

    private void scanAndRepublish() {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(STALE_MINUTES);
        List<Notification> stale = dispatchMapper.findStaleUndispatched(staleBefore, BATCH_LIMIT);
        if (stale.isEmpty()) {
            return;
        }
        log.warn("[Dispatch-Recovery] 发现 {} 条未完成分发的陈旧通知, 开始重投", stale.size());

        int abandoned = 0;
        // 2026-09-30: 投递改为在异步执行器上并行执行。
        // 原实现串行同步投递, broker 不可达时每条都要阻塞到连接超时 (实测可达 30s),
        // 单批 100 条足以卡住整个扫描 (锁 TTL 仅 60s), 使本任务作为"兜底"的承诺落空。
        // 这里用 allOf().join() 等全部完成后才返回, 因此多副本扫描锁的语义保持不变;
        // 重复投递由消费者的 CAS 抢占与幂等键吸收 (本任务本就是至少一次语义)。
        List<CompletableFuture<Void>> inFlight = new ArrayList<>(stale.size());
        AtomicInteger republished = new AtomicInteger();
        for (Notification notification : stale) {
            Long id = notification.getId();
            try {
                if (dispatcher.exhausted(notification)) {
                    dispatchMapper.markDispatchFailed(id);
                    abandoned++;
                    log.error("[Dispatch-Recovery] 重试次数耗尽, 标记失败待人工介入: id={}, attempts={}",
                        id, notification.getDispatchAttempts());
                    continue;
                }

                // 滞留在 DISPATCHING 说明上次消费者中途崩溃, 先退回 PENDING 再重投
                if (notification.getDispatchStatus() == DispatchStatus.DISPATCHING) {
                    dispatchMapper.releaseDispatchClaim(id);
                }

                inFlight.add(CompletableFuture.runAsync(
                    () -> { if (publishToQueue(id)) { republished.incrementAndGet(); } }, taskExecutor));
            } catch (RuntimeException recoveryFailure) {
                log.error("[Dispatch-Recovery] 重投失败: id={}", id, recoveryFailure);
            }
        }
        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();

        if (republished.get() > 0 || abandoned > 0) {
            log.info("[Dispatch-Recovery] 补扫完成: 重投={}, 放弃={}", republished.get(), abandoned);
        }
    }

    /**
     * 投递一条通知到 MQ, 返回是否真的投递成功。
     *
     * <p><b>必须吞掉全部 {@link RuntimeException} (2026-09-30 修复)</b>:
     * 本方法在异步线程上执行, 异常一旦外泄, {@code allOf(...).join()} 会抛
     * {@link java.util.concurrent.CompletionException} 冲出整个扫描批次 —— 主线程那个
     * {@code catch (RuntimeException recoveryFailure)} 接不住异步线程的异常, 于是补扫统计丢失、
     * {@code @Scheduled} 记 error, 且本批次后续条目不再处理。
     * 原先只 catch {@code AmqpException}, 序列化错误、配置错误等一律漏网。</p>
     *
     * <p>投递失败时行仍是 PENDING, 下一轮补扫会再次尝试, 此处只需如实记日志。</p>
     */
    private boolean publishToQueue(Long notificationId) {
        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.NOTIFICATION_DISPATCH_QUEUE, String.valueOf(notificationId));
            return true;
        } catch (RuntimeException brokerFailure) {
            // Broker 不可达: 行仍是 PENDING, 下一轮补扫会再次尝试
            log.error("[Dispatch-Recovery] MQ 投递失败, 下一轮补扫重试: id={}", notificationId,
                brokerFailure);
            return false;
        }
    }
}
