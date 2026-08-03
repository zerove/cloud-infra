package com.cloudinfra.notification;

import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.mapper.NotificationLogMapper;
import com.cloudinfra.notification.mapper.NotificationMapper;
import com.cloudinfra.notification.mapper.NotificationTemplateMapper;
import com.cloudinfra.notification.mapper.ScheduledNotificationMapper;
import com.cloudinfra.notification.repository.NotificationRepository;
import com.cloudinfra.notification.service.ChannelFactory;
import com.cloudinfra.notification.service.INotificationService;
import com.cloudinfra.notification.service.NotificationQuotaService;
import com.cloudinfra.notification.service.NotificationRouter;
import com.cloudinfra.notification.service.channel.NotificationChannel;
import com.cloudinfra.notification.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 通知服务集成测试
 * 验证通知发送 → 渠道路由 → 持久化 完整流程
 * 使用 Mockito mock 外部依赖，聚焦业务编排逻辑
 */
class NotificationServiceIntegrationTest {

    private NotificationMapper notificationMapper;
    private NotificationLogMapper logMapper;
    private NotificationTemplateMapper templateMapper;
    private NotificationRouter notificationRouter;
    private ChannelFactory channelFactory;
    private ScheduledNotificationMapper scheduleMapper;
    private NotificationQuotaService quotaService;

    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        notificationMapper = mock(NotificationMapper.class);
        logMapper = mock(NotificationLogMapper.class);
        templateMapper = mock(NotificationTemplateMapper.class);
        notificationRouter = mock(NotificationRouter.class);
        channelFactory = mock(ChannelFactory.class);
        scheduleMapper = mock(ScheduledNotificationMapper.class);
        quotaService = mock(NotificationQuotaService.class);

        notificationService = new NotificationServiceImpl(
            notificationMapper,
            logMapper,
            templateMapper,
            notificationRouter,
            channelFactory,
            scheduleMapper,
            quotaService
        );
    }

    @Test
    void testFullNotificationFlow() throws Exception {
        // 准备 mock 渠道
        NotificationChannel emailChannel = mock(NotificationChannel.class);
        when(emailChannel.getChannelCode()).thenReturn("email");

        // 路由返回 email 渠道
        when(notificationRouter.route(any())).thenReturn(List.of(emailChannel));

        // 模拟主键回填
        when(notificationMapper.insert(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(1L);
            return 1;
        });

        // 执行发送
        com.cloudinfra.notification.domain.dto.NotificationSendRequest request =
            new com.cloudinfra.notification.domain.dto.NotificationSendRequest(
                1L, "测试标题", "测试内容", NotificationType.EMAIL,
                List.of("email"), null, null, null
            );

        notificationService.sendNotification(request);

        // 验证通知已持久化
        verify(notificationMapper, atLeastOnce()).insert(any(Notification.class));
        verify(notificationMapper, atLeastOnce()).updateById(any(Notification.class));

        // 验证渠道日志已记录
        verify(logMapper, atLeastOnce()).insert(any(com.cloudinfra.notification.domain.NotificationLog.class));
    }

    @Test
    void testChannelSendFailureStillPersists() throws Exception {
        // 准备失败渠道
        NotificationChannel failingChannel = mock(NotificationChannel.class);
        when(failingChannel.getChannelCode()).thenReturn("sms");
        doThrow(new RuntimeException("渠道发送失败")).when(failingChannel)
            .send(any(Long.class), any(String.class), any(String.class));

        when(notificationRouter.route(any())).thenReturn(List.of(failingChannel));
        when(notificationMapper.insert(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(2L);
            return 1;
        });

        com.cloudinfra.notification.domain.dto.NotificationSendRequest request =
            new com.cloudinfra.notification.domain.dto.NotificationSendRequest(
                2L, "失败测试", "内容", NotificationType.SMS,
                List.of("sms"), null, null, null
            );

        notificationService.sendNotification(request);

        // 验证失败渠道的错误日志被记录
        verify(logMapper, atLeastOnce()).insert(argThat(log ->
            "FAIL".equals(((com.cloudinfra.notification.domain.NotificationLog) log).getStatus())
        ));
    }
}