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
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class NotificationServiceImpl implements INotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationLogMapper logMapper;
    private final NotificationTemplateMapper templateMapper;
    private final NotificationRouter notificationRouter;
    private final ScheduledNotificationMapper scheduleMapper;
    private final NotificationQuotaService quotaService;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    /**
     * 异步投递执行器。
     *
     * <p>⚠️ {@code AsyncTaskExecutor} 在上下文里有**两个**候选:
     * <ul>
     *   <li>{@code applicationTaskExecutor} —— 本项目的
     *       {@code VirtualThreadConfig}（虚拟线程）</li>
     *   <li>{@code taskScheduler} —— Spring Boot 的
     *       {@code DefaultTaskSchedulerConfiguration}，而本模块的
     *       {@code spring.task.scheduling} 配置非空时会额外注册它</li>
     * </ul>
     * 两者类型相同，按类型注入即二义，报
     * "Parameter 8 ... required a single bean, but 2 were found" 并启动失败。
     *
     * <p>本地长期复现不了：本地 {@code deploy/nacos/data} 保留了旧的
     * {@code search-service.yaml} 等配置，且此前那份配置里没有
     * {@code spring.task.scheduling} 段，因而 {@code taskScheduler}
     * 那个候选 Bean 根本没被注册。CI 每次 down 后数据目录全新，必然命中。
     *
     * <p>不要改成 {@code @Primary}：那会影响全局其他注入点，
     * 而这里只需要明确“我要的是哪一个”。
     */
    private final Executor taskExecutor;

    /**
     * 显式构造器 —— 不能用 Lombok 的 {@code @RequiredArgsConstructor}。
     *
     * <p><b>这是本类先前“修过但从未生效”的原因</b>（CI run 37074048182, 2026-10-03）。
     * 之前那次修复把 {@code @Qualifier} 加在**字段**上，看起来合理、编译通过、
     * 单测也绿；但 Lombok 生成的构造器**不复制字段上的 {@code @Qualifier}
     * 到构造参数**，而 Spring 注入看的是**构造参数**。
     *
     * <p>实测证据（Lombok 1.18.40 + JDK 25，本机跑的探针）:
     * <pre>
     *   字节码: NotificationServiceImpl 构造器参数级 @Qualifier 数 = 0
     *   探针:  @RequiredArgsConstructor + 字段 @Qualifier -> 参数级 0 个
     *   加上 lombok.config copyableAnnotations += Qualifier -> 1 个
     * </pre>
     *
     * <p>选显式构造器而非 {@code lombok.config}：后者是全局开关，
     * 会改变全仓 Lombok 生成行为；这里只需一处明确意图，作用域越小越好。
     *
     * <p>守护: {@code LombokQualifierPropagationTest} —— 若有人改回
     * {@code @RequiredArgsConstructor}，它会直接红。
     */
    public NotificationServiceImpl(
            NotificationMapper notificationMapper,
            NotificationLogMapper logMapper,
            NotificationTemplateMapper templateMapper,
            NotificationRouter notificationRouter,
            ScheduledNotificationMapper scheduleMapper,
            NotificationQuotaService quotaService,
            ObjectMapper objectMapper,
            RabbitTemplate rabbitTemplate,
            @Qualifier("notificationAsyncExecutor") Executor taskExecutor) {
        this.notificationMapper = notificationMapper;
        this.logMapper = logMapper;
        this.templateMapper = templateMapper;
        this.notificationRouter = notificationRouter;
        this.scheduleMapper = scheduleMapper;
        this.quotaService = quotaService;
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.taskExecutor = taskExecutor;
    }


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
        // 持久化请求的目标渠道: 分发发生在 MQ 消费端, 届时原始请求已不可得。
        // 丢失它会使分发器回落到"所有可用渠道", 导致请求 email 却同时发 sms + websocket。
        notification.setChannels(encodeChannels(request.channels()));
        notificationMapper.insert(notification);

        // 7. 事务提交后投递到 MQ (渠道发送由消费者完成, 失败走 MQ 重试 + DLQ)
        registerDispatchAfterCommit(notification);

        // 8. WebSocket 实时推送 (事务提交后, 用户即时收到通知)
        registerWebSocketPushAfterCommit(notification);
    }

    /**
     * {@code channels} 列的宽度, 与 {@code schema.sql} 及 Flyway V17 中的 {@code VARCHAR(255)} 对齐。
     */
    private static final int MAX_CHANNELS_LENGTH = 255;

    /**
     * 渠道编码为逗号分隔字符串入库; null/空表示由分发器按偏好路由决定。
     *
     * <p><b>超长时明确拒绝而不是截断 (2026-09-30 修复)</b>: 截断会把
     * {@code "email,sms,websocket"} 变成 {@code "email,sms,web"}, 消费端
     * {@code decodeChannels} 解析出并不存在的 {@code web}, {@code NotificationRouter}
     * 找不到该渠道便将其过滤, 结果是<b>用户一条通知都收不到</b>且无任何报错。
     * 静默丢通知比明确报错危险得多, 故超长即抛异常让调用方看见。</p>
     */
    private String encodeChannels(List<String> channels) {
        if (channels == null || channels.isEmpty()) {
            return null;
        }
        List<String> cleaned = channels.stream()
            .filter(c -> c != null && !c.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        if (cleaned.isEmpty()) {
            return null;
        }
        String encoded = String.join(",", cleaned);
        if (encoded.length() > MAX_CHANNELS_LENGTH) {
            throw new IllegalArgumentException(
                "渠道集合编码后长度 " + encoded.length() + " 超过列宽 " + MAX_CHANNELS_LENGTH
                    + ", 拒绝入库以免截断导致渠道丢失: " + cleaned);
        }
        return encoded;
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
     * <p><b>投递本身也必须离开请求线程 (2026-09-30 修复)</b>: {@code convertAndSend}
     * 在 broker 不可达时会阻塞到连接超时, 而这段代码运行在 {@code afterCommit} 中 ——
     * 即请求线程上。于是 MQ 抖动会拖住已提交事务的用户请求直至超时, 并占用 Tomcat 工作线程;
     * {@code catch (AmqpException)} 挡不住这种阻塞, 它发生在异常抛出之前。
     * 交由异步执行器后请求立即返回。</p>
     *
     * <p><b>异步化不损失可靠性</b>: 通知行已带 {@code dispatch_status=PENDING}, 崩溃或投递
     * 失败时 {@code NotificationDispatchRecoveryTask} 会扫描陈旧未完成的行并重投。</p>
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
                        taskExecutor.execute(publish);
                    }
                }
            );
        } else {
            // 无事务上下文（如 MQ 回调中的非事务路径）：同样不占用调用线程
            taskExecutor.execute(publish);
        }
    }

    private void publishToQueue(Long notificationId) {
        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.NOTIFICATION_DISPATCH_QUEUE, String.valueOf(notificationId));
            log.debug("[Dispatch-Publish] 已投递到 MQ: notificationId={}", notificationId);
        } catch (AmqpException brokerFailure) {
            // 不阻断调用方 (已是异步线程): 通知行已在库中且仍是 PENDING, 补扫任务会重投
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
