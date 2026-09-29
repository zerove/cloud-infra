package com.cloudinfra.notification.service.impl;

import com.cloudinfra.api.notification.enums.DispatchStatus;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.common.core.exception.SystemExceptionCode;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.domain.NotificationTemplate;
import com.cloudinfra.notification.domain.ScheduledNotification;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.mapper.NotificationLogMapper;
import com.cloudinfra.notification.mapper.NotificationMapper;
import com.cloudinfra.notification.mapper.NotificationTemplateMapper;
import com.cloudinfra.notification.mapper.ScheduledNotificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cloudinfra.notification.service.NotificationQuotaService;
import com.cloudinfra.notification.service.NotificationRouter;
import com.cloudinfra.notification.service.channel.NotificationChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock private NotificationMapper notificationMapper;
    @Mock private NotificationLogMapper logMapper;
    @Mock private NotificationTemplateMapper templateMapper;
    @Mock private NotificationRouter notificationRouter;
    @Mock private ScheduledNotificationMapper scheduleMapper;
    @Mock private NotificationQuotaService quotaService;
    @Mock private ObjectMapper objectMapper;
    @Mock private RabbitTemplate rabbitTemplate;

    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationServiceImpl(
            notificationMapper, logMapper, templateMapper,
            notificationRouter, scheduleMapper, quotaService, objectMapper,
            rabbitTemplate
        );
    }

    @Test
    void sendNotification_withSimpleContent_shouldSucceed() throws Exception {
        // given
        NotificationSendRequest request = new NotificationSendRequest(
            1L, "Hello", "World", NotificationType.EMAIL,
            List.of("email"), null, null, null
        );

        NotificationChannel emailChannel = mock(NotificationChannel.class);
        lenient().when(emailChannel.getChannelCode()).thenReturn("email");

        given(notificationRouter.route(request)).willReturn(List.of(emailChannel));
        given(notificationMapper.insert(any(Notification.class))).will(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(100L);
            return 1;
        });

        // when
        notificationService.sendNotification(request);

        // then
        then(quotaService).should().checkQuota(1L);
        then(notificationMapper).should().insert(any(Notification.class));
    }

    @Test
    void sendNotification_withTemplate_shouldRenderTemplate() throws Exception {
        // given
        NotificationSendRequest request = new NotificationSendRequest(
            1L, null, null, NotificationType.EMAIL,
            List.of("email"), "WELCOME", Map.of("name", "Alice"), null
        );

        NotificationTemplate template = new NotificationTemplate();
        template.setTitleTemplate("Welcome ${name}");
        template.setContentTemplate("Hello ${name}, welcome!");

        given(templateMapper.selectOne(any())).willReturn(template);

        NotificationChannel emailChannel = mock(NotificationChannel.class);
        lenient().when(emailChannel.getChannelCode()).thenReturn("email");
        given(notificationRouter.route(request)).willReturn(List.of(emailChannel));
        given(notificationMapper.insert(any(Notification.class))).will(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(101L);
            return 1;
        });

        // when
        notificationService.sendNotification(request);

        // then
        then(templateMapper).should().selectOne(any());
        then(notificationMapper).should().insert(any(Notification.class));
    }

    @Test
    void sendNotification_whenQuotaExceeded_shouldThrowException() {
        // given
        NotificationSendRequest request = new NotificationSendRequest(
            1L, "Test", "Content", NotificationType.EMAIL,
            List.of("email"), null, null, null
        );

        doThrow(new BusinessException(SystemExceptionCode.RESOURCE_EXHAUSTED))
            .when(quotaService).checkQuota(1L);

        // when / then
        assertThatThrownBy(() -> notificationService.sendNotification(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("资源配额已用尽");

        then(notificationMapper).should(never()).insert(any(Notification.class));
    }

    @Test
    void sendNotification_scheduled_shouldSaveSchedule() throws Exception {
        // given
        LocalDateTime future = LocalDateTime.now().plusHours(1);
        NotificationSendRequest request = new NotificationSendRequest(
            1L, "Scheduled", "Later", NotificationType.EMAIL,
            null, null, Map.of("key", "val"), future
        );

        // when
        notificationService.sendNotification(request);

        // then
        then(quotaService).should().checkQuota(1L);
        then(scheduleMapper).should().insert(any(ScheduledNotification.class));
        then(notificationMapper).should(never()).insert(any(Notification.class));
        then(notificationRouter).should(never()).route(any());
    }

    @Test
    void sendNotification_withoutTitle_whenNoTemplate_shouldThrow() {
        // given
        NotificationSendRequest request = new NotificationSendRequest(
            1L, null, "content only", NotificationType.EMAIL,
            null, null, null, null
        );

        // when / then
        assertThatThrownBy(() -> notificationService.sendNotification(request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sendNotification_shouldPersistAsPendingThenPublishToQueue() throws Exception {
        // given: 通知落库后应为 PENDING, 并投递到 MQ 等待消费者分发
        // (原先是"入库 → 内存线程池直接发渠道", 进程崩溃即丢失; 见 issue 27 决策 2)
        NotificationSendRequest request = new NotificationSendRequest(
            1L, "Hello", "World", NotificationType.EMAIL,
            List.of("email"), null, null, null
        );

        given(notificationRouter.route(request)).willReturn(List.of());
        given(notificationMapper.insert(any(Notification.class))).will(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(102L);
            return 1;
        });

        // when
        notificationService.sendNotification(request);

        // then
        ArgumentCaptor<Notification> persisted = ArgumentCaptor.forClass(Notification.class);
        then(notificationMapper).should().insert(persisted.capture());
        assertThat(persisted.getValue().getDispatchStatus()).isEqualTo(DispatchStatus.PENDING);
        assertThat(persisted.getValue().getDispatchAttempts()).isZero();

        then(rabbitTemplate).should()
            .convertAndSend("notification.dispatch.queue", "102");
    }

    @Test
    void sendNotification_whenBrokerUnreachable_shouldNotFailRequest() throws Exception {
        // given: MQ 不可达时业务仍应成功 —— 通知行留在库中且为 PENDING, 补扫任务会重投
        NotificationSendRequest request = new NotificationSendRequest(
            1L, "Hello", "World", NotificationType.EMAIL,
            List.of("email"), null, null, null
        );

        given(notificationRouter.route(request)).willReturn(List.of());
        given(notificationMapper.insert(any(Notification.class))).will(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(103L);
            return 1;
        });
        doThrow(new AmqpException("broker down"))
            .when(rabbitTemplate).convertAndSend(
                eq("notification.dispatch.queue"), any(Object.class));

        // when / then: 不抛异常
        notificationService.sendNotification(request);

        then(notificationMapper).should().insert(any(Notification.class));
    }

    @Test
    void getNotificationHistory_shouldReturnList() {
        // given
        Long userId = 1L;
        Notification notification = new Notification();
        notification.setId(1L);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Notification> page =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        page.setRecords(List.of(notification));
        given(notificationMapper.selectPage(any(), any())).willReturn(page);

        // when
        List<Notification> result = notificationService.getNotificationHistory(userId, 1, 10);

        // then
        assertThat(result).hasSize(1);
    }

    @Test
    void searchNotifications_shouldReturnMatchingResults() {
        // given
        String keyword = "test";
        List<Notification> expected = List.of(new Notification());
        given(notificationMapper.selectList(any())).willReturn(expected);

        // when
        List<Notification> result = notificationService.searchNotifications(keyword);

        // then
        assertThat(result).hasSize(1);
    }

    @Test
    void getLogs_shouldReturnLogList() {
        // given
        Long userId = 1L;
        Notification notification = new Notification();
        notification.setId(1L);
        List<Notification> notifications = List.of(notification);
        List<NotificationLog> expected = List.of(new NotificationLog());
        given(notificationMapper.selectList(any())).willReturn(notifications);
        given(logMapper.selectList(any())).willReturn(expected);

        // when
        List<NotificationLog> result = notificationService.getLogs(userId);

        // then
        assertThat(result).hasSize(1);
    }
}
