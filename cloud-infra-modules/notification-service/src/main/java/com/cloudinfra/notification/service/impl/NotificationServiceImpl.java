package com.cloudinfra.notification.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.domain.NotificationTemplate;
import com.cloudinfra.notification.domain.ScheduledNotification;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.mapper.NotificationLogMapper;
import com.cloudinfra.notification.mapper.NotificationMapper;
import com.cloudinfra.notification.mapper.NotificationTemplateMapper;
import com.cloudinfra.notification.mapper.ScheduledNotificationMapper;
import com.cloudinfra.notification.service.ChannelFactory;
import com.cloudinfra.notification.service.INotificationService;
import com.cloudinfra.notification.service.NotificationRouter;
import com.cloudinfra.notification.service.NotificationQuotaService;
import com.cloudinfra.notification.service.channel.NotificationChannel;
import com.cloudinfra.notification.util.TemplateProcessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements INotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationLogMapper logMapper;
    private final NotificationTemplateMapper templateMapper;
    private final NotificationRouter notificationRouter;
    private final ChannelFactory channelFactory;
    private final ScheduledNotificationMapper scheduleMapper;
    private final NotificationQuotaService quotaService;
    private final ObjectMapper objectMapper;
    private final ExecutorService dispatchExecutor;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sendNotification(NotificationSendRequest request) {
        // 1. 请求参数校验
        request.validate();

        // 2. 发送频率配额校验 (基于 Redis 的日计数)
        quotaService.checkQuota(request.userId());

        // 3. 检查是否为定时发送
        if (request.scheduleTime() != null) {
            scheduleNotification(request);
            return;
        }

        // 4. 处理内容：模版渲染 vs 纯文本
        String finalTitle;
        String finalContent;

        if (request.templateCode() != null && !request.templateCode().isBlank()) {
            NotificationTemplate template = templateMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NotificationTemplate>()
                    .eq(NotificationTemplate::getTemplateCode, request.templateCode())
            );

            if (template == null) {
                log.warn("未找到模版: {}, 将退回到纯文本模式", request.templateCode());
                finalTitle = request.title();
                finalContent = request.content();
            } else {
                finalTitle = TemplateProcessor.process(template.getTitleTemplate(), request.params());
                finalContent = TemplateProcessor.process(template.getContentTemplate(), request.params());
            }
        } else {
            finalTitle = request.title();
            finalContent = request.content();
        }

        // 5. 智能路由：计算最终触达渠道
        List<NotificationChannel> targetChannels = notificationRouter.route(request);

        // 6. 持久化通知主记录 (状态: UNREAD - 待处理)
        Notification notification = new Notification();
        notification.setUserId(request.userId());
        notification.setTitle(finalTitle);
        notification.setContent(finalContent);
        notification.setType(request.type());
        notification.setStatus(NotificationStatus.UNREAD);
        notificationMapper.insert(notification);

        // 7. 事务提交后分发渠道（避免长事务持有 DB 连接）
        registerDispatchAfterCommit(notification, targetChannels);
    }

    private void scheduleNotification(NotificationSendRequest request) {
        ScheduledNotification schedule = new ScheduledNotification();
        schedule.setUserId(request.userId());
        schedule.setTemplateCode(request.templateCode());
        schedule.setType(request.type().name());
        schedule.setScheduleTime(request.scheduleTime());
        try {
            schedule.setParamsJson(objectMapper.writeValueAsString(request.params()));
        } catch (JsonProcessingException e) {
            log.error("参数序列化失败: {}", e.getMessage());
            throw new RuntimeException("Failed to serialize params", e);
        }
        scheduleMapper.insert(schedule);
        log.info("通知已加入调度队列, 预计执行时间: {}", request.scheduleTime());
    }

    /**
     * 事务提交后分发渠道
     *
     * 通过 TransactionSynchronization 保证渠道发送（IO 密集、失败重试）在
     * 主记录持久化事务提交之后执行，避免长事务持有数据库连接、渠道失败回滚 DB。
     */
    private void registerDispatchAfterCommit(Notification notification, List<NotificationChannel> targetChannels) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatchChannels(notification, targetChannels);
                    }
                }
            );
        } else {
            // 无事务上下文（如 MQ 回调中的非事务路径）：直接分发
            dispatchChannels(notification, targetChannels);
        }
    }

    /**
     * 并发分发到各渠道（非事务，独立记录渠道日志）
     * 异步执行，不阻塞调用线程：分发完成后更新通知状态。
     */
    private void dispatchChannels(Notification notification, List<NotificationChannel> targetChannels) {
        dispatchExecutor.execute(() -> {
            List<CompletableFuture<Boolean>> futures = targetChannels.stream()
                .map(channel -> CompletableFuture.supplyAsync(() -> {
                    long startTime = System.currentTimeMillis();
                    String code = channel.getChannelCode();
                    try {
                        channel.send(notification.getUserId(), notification.getTitle(), notification.getContent());
                        saveLog(notification.getId(), code, "SUCCESS", null, System.currentTimeMillis() - startTime);
                        log.info("[VT-Worker] 渠道 {} 发送成功 -> 用户: {}", code, notification.getUserId());
                        return true;
                    } catch (Exception e) {
                        saveLog(notification.getId(), code, "FAIL", e.getMessage(), System.currentTimeMillis() - startTime);
                        log.error("[VT-Worker] 渠道 {} 发送失败: {}", code, e.getMessage());
                        return false;
                    }
                }, dispatchExecutor))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 只要有一个渠道发送成功，状态即标记为 SENT
            boolean anySuccess = futures.stream().anyMatch(f -> f.join());
            NotificationStatus newStatus = anySuccess ? NotificationStatus.SENT : NotificationStatus.UNREAD;
            if (newStatus != notification.getStatus()) {
                Notification update = new Notification();
                update.setId(notification.getId());
                update.setStatus(newStatus);
                notificationMapper.updateById(update);
            }
        });
    }

    private void saveLog(Long notificationId, String channelCode, String status, String error, long duration) {
        NotificationLog logEntry = new NotificationLog();
        logEntry.setNotificationId(notificationId);
        logEntry.setChannelCode(channelCode);
        logEntry.setStatus(status);
        logEntry.setErrorMessage(error);
        logEntry.setDuration(duration);
        logMapper.insert(logEntry);
    }

    @Override
    public List<NotificationLog> getLogs(Long userId) {
        // 1. 获取用户的所有通知 ID
        List<Notification> notifications = notificationMapper.selectList(
            new LambdaQueryWrapper<Notification>().eq(Notification::getUserId, userId)
        );
        if (notifications == null || notifications.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> notificationIds = notifications.stream()
            .map(Notification::getId)
            .collect(Collectors.toList());

        // 2. 根据通知 ID 集合检索所有渠道日志
        return logMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NotificationLog>()
                .in(notificationIds.isEmpty(),
                    NotificationLog::getNotificationId, notificationIds)
                .orderByDesc(NotificationLog::getCreateTime)
        );
    }

    @Override
    public List<Notification> searchNotifications(String keyword) {
        return notificationMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notification>()
                .and(wrapper -> wrapper.like(Notification::getTitle, keyword)
                                       .or()
                                       .like(Notification::getContent, keyword))
        );
    }

    @Override
    public List<Notification> getNotificationHistory(Long userId, int page, int size) {
        LambdaQueryWrapper<Notification> wrapper =
            new LambdaQueryWrapper<Notification>().eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreateTime);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Notification> p =
            notificationMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size),
                wrapper);
        return p.getRecords();
    }
}