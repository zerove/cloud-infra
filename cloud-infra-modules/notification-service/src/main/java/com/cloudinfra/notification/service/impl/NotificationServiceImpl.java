package com.cloudinfra.notification.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import static com.cloudinfra.notification.ws.NotificationWebSocketHandler.pushToUser;
import com.cloudinfra.api.notification.enums.DispatchStatus;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.notification.config.RabbitMQConfig;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.domain.NotificationTemplate;
import com.cloudinfra.notification.domain.ScheduledNotification;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.mapper.NotificationLogMapper;
import com.cloudinfra.notification.mapper.NotificationMapper;
import com.cloudinfra.notification.mapper.NotificationTemplateMapper;
import com.cloudinfra.notification.mapper.ScheduledNotificationMapper;
import com.cloudinfra.notification.service.INotificationService;
import com.cloudinfra.notification.service.NotificationRouter;
import com.cloudinfra.notification.service.NotificationQuotaService;
import com.cloudinfra.notification.util.TemplateProcessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements INotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationLogMapper logMapper;
    private final NotificationTemplateMapper templateMapper;
    private final NotificationRouter notificationRouter;
    private final ScheduledNotificationMapper scheduleMapper;
    private final NotificationQuotaService quotaService;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;


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

        // 5. 智能路由：校验并计算最终触达渠道
        //    路由结果在消费者侧按通知记录重新计算, 此处仅做配额/偏好校验以尽早失败
        notificationRouter.route(request);

        // 6. 持久化通知主记录
        //    dispatch_status=PENDING: 本行同时充当发件箱记录。事务提交后由 MQ 投递;
        //    若进程在提交后、投递前崩溃, 补扫任务会发现此行并重投, 通知不会静默丢失。
        Notification notification = new Notification();
        notification.setUserId(request.userId());
        notification.setTitle(finalTitle);
        notification.setContent(finalContent);
        notification.setType(request.type());
        notification.setStatus(NotificationStatus.UNREAD);
        notification.setDispatchStatus(DispatchStatus.PENDING);
        notification.setDispatchAttempts(0);
        notificationMapper.insert(notification);

        // 7. 事务提交后投递到 MQ (渠道发送由消费者完成, 失败走 MQ 重试 + DLQ)
        registerDispatchAfterCommit(notification);

        // 8. WebSocket 实时推送 (事务提交后, 用户即时收到通知)
        registerWebSocketPushAfterCommit(notification);
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
     * 事务提交后把通知投递到 MQ (issue 27 决策 2)
     *
     * <p>不在此处直接发送渠道: 内存线程池在事务提交与实际投递之间的崩溃窗口会让通知
     * 永久丢失。改为投递到 MQ 后, 消息落在 Broker 上, 进程重启不丢。</p>
     *
     * <p><b>失败重投不依赖 MQ 重试</b> (本项目未配置原生监听器级重试/DLQ), 而是由
     * {@code NotificationDispatchRecoveryTask} 依据 {@code dispatch_status} 承担。
     * 即使此处 MQ 投递本身失败 (Broker 不可达), 通知行仍留在库中且为 PENDING,
     * 补扫任务会重新投递。</p>
     */
    private void registerDispatchAfterCommit(Notification notification) {
        Runnable publish = () -> publishToQueue(notification.getId());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publish.run();
                    }
                }
            );
        } else {
            // 无事务上下文（如 MQ 回调中的非事务路径）：直接投递
            publish.run();
        }
    }

    private void publishToQueue(Long notificationId) {
        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.NOTIFICATION_DISPATCH_QUEUE, String.valueOf(notificationId));
            log.debug("[Dispatch-Publish] 已投递到 MQ: notificationId={}", notificationId);
        } catch (AmqpException brokerFailure) {
            // 不阻断业务: 通知行已在库中且仍是 PENDING, 补扫任务会重投
            log.error("[Dispatch-Publish] MQ 投递失败, 交由补扫重投: notificationId={}", notificationId,
                brokerFailure);
        }
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

    /**
     * 事务提交后 WebSocket 推送
     * 通过 TransactionSynchronization 保证 DB 已持久化后再推, 避免幻读.
     */
    private void registerWebSocketPushAfterCommit(Notification notification) {
        Map<String, Object> payload = Map.of(
            "id", notification.getId(),
            "type", notification.getType().name(),
            "title", notification.getTitle(),
            "content", notification.getContent(),
            "status", notification.getStatus().name(),
            "createTime", notification.getCreateTime() != null
                ? notification.getCreateTime().toString() : ""
        );
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        pushToUser(notification.getUserId(), payload);
                    }
                });
        } else {
            // 无事务上下文 (异步调度), 直接推
            pushToUser(notification.getUserId(), payload);
        }
    }
}
