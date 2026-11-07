package com.cloudinfra.notification.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.api.notification.enums.DispatchStatus;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.domain.PageQuery;
import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.common.core.exception.ErrorCode;
import com.cloudinfra.common.core.exception.SystemExceptionCode;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.domain.NotificationTemplate;
import com.cloudinfra.notification.domain.ScheduledNotification;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.mapper.NotificationMapper;
import com.cloudinfra.notification.mapper.NotificationTemplateMapper;
import com.cloudinfra.notification.mapper.ScheduledNotificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cloudinfra.notification.service.NotificationPushGateway;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock private NotificationMapper notificationMapper;
    @Mock private NotificationTemplateMapper templateMapper;
    @Mock private NotificationRouter notificationRouter;
    @Mock private ScheduledNotificationMapper scheduleMapper;
    @Mock private NotificationQuotaService quotaService;
    @Mock private NotificationPushGateway pushGateway;
    @Mock private ObjectMapper objectMapper;
    @Mock private RabbitTemplate rabbitTemplate;

    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationServiceImpl(
            notificationMapper, templateMapper,
            notificationRouter, scheduleMapper, quotaService, pushGateway, objectMapper,
            rabbitTemplate,
            // 同步执行器: 投递在 afterCommit 中立即完成, 保持单测确定性
            new org.springframework.core.task.support.TaskExecutorAdapter(Runnable::run)
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
    void getNotificationHistory_shouldReturnPageResult() {
        // given
        Page<Notification> page = new Page<>(1, 10);
        page.setRecords(List.of(new Notification()));
        page.setTotal(3);
        given(notificationMapper.selectPage(any(), any())).willReturn(page);

        // when
        PageResult<Notification> result =
            notificationService.getNotificationHistory(1L, null, null, new PageQuery());

        // then
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(3);
    }

    @Test
    void getNotificationHistory_shouldClampOutOfRangePageWindow() {
        // given: pageNum=0 / pageSize=999 越界, 期望钳制为 1 / 100
        PageQuery pageQuery = new PageQuery();
        pageQuery.setPageNum(0);
        pageQuery.setPageSize(999);
        Page<Notification> returned = new Page<>(1, 100);
        returned.setRecords(List.of(new Notification()));
        returned.setTotal(7);
        given(notificationMapper.selectPage(any(), any())).willReturn(returned);

        // when
        PageResult<Notification> result =
            notificationService.getNotificationHistory(1L, null, null, pageQuery);

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<Notification>> captor = ArgumentCaptor.forClass(Page.class);
        then(notificationMapper).should().selectPage(captor.capture(), any());
        assertThat(captor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(captor.getValue().getSize()).isEqualTo(100L);
        assertThat(result.getTotal()).isEqualTo(7);
    }

    @Test
    void searchNotifications_shouldReturnPageResult() {
        // given
        Page<Notification> page = new Page<>(1, 10);
        page.setRecords(List.of(new Notification()));
        page.setTotal(1);
        given(notificationMapper.selectPage(any(), any())).willReturn(page);

        // when
        PageResult<Notification> result = notificationService.searchNotifications("test", new PageQuery());

        // then
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
    }

    @Test
    void getLogs_shouldReturnLogPageResult() {
        // given
        Page<NotificationLog> page = new Page<>(1, 10);
        page.setRecords(List.of(new NotificationLog()));
        page.setTotal(2);
        given(notificationMapper.selectLogPageByUserId(any(), eq(1L))).willReturn(page);

        // when
        PageResult<NotificationLog> result = notificationService.getLogs(1L, new PageQuery());

        // then
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(2);
    }

    // ---- 配额回滚补偿: checkQuota 的 Redis INCR 不受 DB 事务管理,
    //      回滚后计数会泄漏; 以下用例手工驱动事务同步器验证补偿边界 ----

    @Test
    void sendNotification_whenTransactionRollsBack_shouldRefundQuota() throws Exception {
        // given
        NotificationSendRequest request = simpleRequest();
        given(notificationRouter.route(request)).willReturn(List.of());
        given(notificationMapper.insert(any(Notification.class))).will(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(200L);
            return 1;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            // when: 业务执行成功, 但事务最终回滚 (如提交前其他环节抛错)
            notificationService.sendNotification(request);
            fireAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            // then: INCR 随回滚归还, 计数不泄漏
            then(quotaService).should().refundQuota(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sendNotification_whenCommitted_shouldNotRefundQuota() throws Exception {
        // given
        NotificationSendRequest request = simpleRequest();
        given(notificationRouter.route(request)).willReturn(List.of());
        given(notificationMapper.insert(any(Notification.class))).will(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(201L);
            return 1;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            // when: 正常提交 (afterCommit → afterCompletion(COMMITTED))
            notificationService.sendNotification(request);
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
            fireAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

            // then: 已提交的发送保留配额占用
            then(quotaService).should(never()).refundQuota(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sendNotification_whenCommitOutcomeUnknown_shouldNotRefundQuota() throws Exception {
        // given: UNKNOWN = 提交结果未知; 不归还, 以免漏计可能已提交的发送
        NotificationSendRequest request = simpleRequest();
        given(notificationRouter.route(request)).willReturn(List.of());
        given(notificationMapper.insert(any(Notification.class))).will(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(202L);
            return 1;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            // when
            notificationService.sendNotification(request);
            fireAfterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

            // then
            then(quotaService).should(never()).refundQuota(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sendNotification_whenQuotaExceededAndRolledBack_shouldNotRefund() {
        // given: 超限时 checkQuota 已内部归还 (INCR 后 decrement); 若回滚时再归还一次,
        // 每次被拒反而净扣 1, 连续重试甚至能重新解锁发送
        NotificationSendRequest request = simpleRequest();
        doThrow(new BusinessException(SystemExceptionCode.RESOURCE_EXHAUSTED))
            .when(quotaService).checkQuota(1L);

        TransactionSynchronizationManager.initSynchronization();
        try {
            // when
            assertThatThrownBy(() -> notificationService.sendNotification(request))
                .isInstanceOf(BusinessException.class);
            fireAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            // then: 未发生扣减的事务没有可归还的额度
            then(quotaService).should(never()).refundQuota(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sendNotification_whenRefundFails_shouldNotPropagate() throws Exception {
        // given: 回滚补偿是尽力而为 —— 归还失败不能掩盖回滚的真实原因
        NotificationSendRequest request = simpleRequest();
        given(notificationRouter.route(request)).willReturn(List.of());
        given(notificationMapper.insert(any(Notification.class))).will(invocation -> {
            Notification n = invocation.getArgument(0);
            n.setId(203L);
            return 1;
        });
        doThrow(new RedisConnectionFailureException("redis down"))
            .when(quotaService).refundQuota(1L);

        TransactionSynchronizationManager.initSynchronization();
        try {
            notificationService.sendNotification(request);

            // when / then: 归还抛错被吞掉记日志, 不向事务管理器外泄
            assertThatCode(() -> fireAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK))
                .doesNotThrowAnyException();
            then(quotaService).should().refundQuota(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---- markAsRead: 归属校验 + 幂等 + 局部更新 (issue 12) ----

    @Test
    void markAsRead_unreadOwnedNotification_shouldSetReadWithPartialUpdate() {
        // given
        Notification existing = new Notification();
        existing.setId(300L);
        existing.setUserId(1L);
        existing.setStatus(NotificationStatus.UNREAD);
        existing.setTitle("原标题");
        existing.setDispatchStatus(DispatchStatus.DISPATCHED);
        existing.setChannels("email");
        given(notificationMapper.selectById(300L)).willReturn(existing);

        // when
        notificationService.markAsRead(300L, 1L);

        // then
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        then(notificationMapper).should().updateById(captor.capture());
        Notification update = captor.getValue();
        assertThat(update.getId()).isEqualTo(300L);
        assertThat(update.getStatus()).isEqualTo(NotificationStatus.READ);
        assertThat(update.getReadTime()).isNotNull();
        // 局部更新: 不携带 title/dispatch 字段, 避免陈旧快照覆盖分发器并发写入
        assertThat(update.getTitle()).isNull();
        assertThat(update.getDispatchStatus()).isNull();
        assertThat(update.getDispatchAttempts()).isNull();
        assertThat(update.getChannels()).isNull();
    }

    @Test
    void markAsRead_whenNotificationNotFound_shouldThrow1006AndNotWrite() {
        // given
        given(notificationMapper.selectById(999L)).willReturn(null);

        // when / then
        assertThatThrownBy(() -> notificationService.markAsRead(999L, 1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("通知不存在")
            .hasFieldOrPropertyWithValue("code", ErrorCode.RESOURCE_NOT_FOUND.getCode());

        then(notificationMapper).should(never()).updateById(any(Notification.class));
    }

    @Test
    void markAsRead_whenNotOwner_shouldThrow1005AndNotWrite() {
        // given: 通知属于用户 7, 操作用户是 1
        Notification existing = new Notification();
        existing.setId(301L);
        existing.setUserId(7L);
        existing.setStatus(NotificationStatus.UNREAD);
        given(notificationMapper.selectById(301L)).willReturn(existing);

        // when / then
        assertThatThrownBy(() -> notificationService.markAsRead(301L, 1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权操作该通知")
            .hasFieldOrPropertyWithValue("code", ErrorCode.NO_PERMISSION.getCode());

        then(notificationMapper).should(never()).updateById(any(Notification.class));
    }

    @Test
    void markAsRead_whenAlreadyRead_shouldPreserveFirstReadTimeAndNotWrite() {
        // given: 已读通知带首次已读时间
        LocalDateTime firstRead = LocalDateTime.of(2026, 10, 17, 9, 0);
        Notification existing = new Notification();
        existing.setId(302L);
        existing.setUserId(1L);
        existing.setStatus(NotificationStatus.READ);
        existing.setReadTime(firstRead);
        given(notificationMapper.selectById(302L)).willReturn(existing);

        // when
        notificationService.markAsRead(302L, 1L);

        // then: 幂等 —— 不写库, 首次已读时间不被覆盖
        then(notificationMapper).should(never()).updateById(any(Notification.class));
        assertThat(existing.getReadTime()).isEqualTo(firstRead);
    }

    @Test
    void markAsRead_whenNoAuthenticatedUser_shouldFailClosed() {
        // given: 无安全上下文 -> userId 为 null, 不得默认放行
        Notification existing = new Notification();
        existing.setId(303L);
        existing.setUserId(1L);
        existing.setStatus(NotificationStatus.UNREAD);
        given(notificationMapper.selectById(303L)).willReturn(existing);

        // when / then
        assertThatThrownBy(() -> notificationService.markAsRead(303L, null))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("code", ErrorCode.NO_PERMISSION.getCode());

        then(notificationMapper).should(never()).updateById(any(Notification.class));
    }

    private static void fireAfterCompletion(int status) {
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(status));
    }

    private static NotificationSendRequest simpleRequest() {
        return new NotificationSendRequest(
            1L, "Hello", "World", NotificationType.EMAIL,
            List.of("email"), null, null, null);
    }
}
