package com.cloudinfra.notification.service;

import com.cloudinfra.notification.domain.ScheduledNotification;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.mapper.ScheduledNotificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 通知调度引擎 - 负责定时通知的扫描与触发行
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private static final String SCHEDULE_LOCK_KEY = "notification:schedule:lock";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final ScheduledNotificationMapper scheduleMapper;
    private final INotificationService notificationService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 每分钟扫描一次到期的通知任务
     * 通过 Redis SET NX EX 分布式锁保证多副本部署下同一时刻仅一个实例执行扫描，
     * 且逐条任务标记先处理（CAS），避免重复发送。
     */
    @Scheduled(cron = "0 * * * * *")
    public void processScheduledNotifications() {
        // 1. 获取分布式锁（非阻塞，拿不到说明其他副本正在扫描）
        String lockValue = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
            SCHEDULE_LOCK_KEY, lockValue, LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("其他实例正在执行通知调度扫描，本次跳过");
            return;
        }

        try {
            scanAndProcess();
        } finally {
            // 释放锁（仅当值匹配，防止误删他人锁）
            String current = redisTemplate.opsForValue().get(SCHEDULE_LOCK_KEY);
            if (lockValue.equals(current)) {
                redisTemplate.delete(SCHEDULE_LOCK_KEY);
            }
        }
    }

    private void scanAndProcess() {
        log.info("开始扫描到期通知任务...");
        
        // 查找待处理且已到期的通知，限制每次处理数量防止内存溢出
        List<ScheduledNotification> dueTasks = scheduleMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ScheduledNotification>()
                .eq(ScheduledNotification::getProcessed, false)
                .le(ScheduledNotification::getScheduleTime, LocalDateTime.now())
                .last("LIMIT 100")
        );

        if (dueTasks.isEmpty()) {
            log.info("当前无到期任务。");
            return;
        }

        log.info("发现 {} 个到期任务，准备发送...", dueTasks.size());

        for (ScheduledNotification task : dueTasks) {
            try {
                // 原子抢占：仅当 processed=false 时标记为 true，防止并发重复处理
                int claimed = scheduleMapper.markProcessedIfPending(task.getId());
                if (claimed == 0) {
                    log.info("调度任务 {} 已被其他实例处理，跳过", task.getId());
                    continue;
                }

                // 将调度任务转换为发送请求
                NotificationSendRequest request = new NotificationSendRequest(
                    task.getUserId(),
                    null, // 模版发送不需要 title
                    null, // 模版发送不需要 content
                    com.cloudinfra.api.notification.enums.NotificationType.valueOf(task.getType()),
                    null, // 使用模版默认渠道
                    task.getTemplateCode(),
                    task.getParams(objectMapper),
                    null // 已到期，无需再次调度
                );

                // 执行发送
                notificationService.sendNotification(request);
                
                log.info("调度任务 {} 发送成功", task.getId());
            } catch (Exception e) {
                log.error("调度任务 {} 执行失败: {}", task.getId(), e.getMessage());
                // 失败不重试：保持 processed=true（同 sendNotification 失败语义），
                // 避免失败任务无限循环重发；如需重试可引入退避重试状态机。
            }
        }
    }
}