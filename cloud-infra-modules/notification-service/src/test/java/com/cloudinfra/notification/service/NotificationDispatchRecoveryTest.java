package com.cloudinfra.notification.service;

import com.cloudinfra.api.notification.enums.DispatchStatus;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.mapper.NotificationDispatchMapper;
import com.cloudinfra.notification.mq.NotificationDispatchConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 通知分发可靠性测试 (issue 27 决策 2)
 *
 * <p>覆盖两条链路的去重与崩溃恢复语义:
 * <ul>
 *   <li>消费者: CAS 抢占保证 MQ 重投不会导致重复分发</li>
 *   <li>补扫任务: 找回 PENDING/DISPATCHING 的陈旧通知并重投, 达上限则放弃</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationDispatchRecoveryTest {

    @Mock private NotificationDispatchMapper dispatchMapper;
    @Mock private NotificationDispatcher dispatcher;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private NotificationDispatchConsumer consumer;
    private NotificationDispatchRecoveryTask recoveryTask;

    @BeforeEach
    void setUp() {
        consumer = new NotificationDispatchConsumer(dispatchMapper, dispatcher);
        recoveryTask = new NotificationDispatchRecoveryTask(
            dispatchMapper, rabbitTemplate, redisTemplate, dispatcher);
        given(redisTemplate.opsForValue()).willReturn(valueOps);
    }

    @Nested
    @DisplayName("消费者: CAS 抢占去重")
    class ConsumerTests {

        @Test
        @DisplayName("抢占成功才执行分发")
        void dispatchesWhenClaimSucceeds() {
            given(dispatchMapper.claimDispatch(7L, DispatchStatus.PENDING)).willReturn(1);

            consumer.handleDispatch("7");

            verify(dispatcher).dispatch(7L);
        }

        @Test
        @DisplayName("抢占失败则跳过, 避免重复分发 (MQ 重投场景)")
        void skipsWhenClaimLost() {
            // 受影响行数 0 = 状态已被其他消费者改动 (DISPATCHING/DISPATCHED/FAILED)
            given(dispatchMapper.claimDispatch(7L, DispatchStatus.PENDING)).willReturn(0);

            consumer.handleDispatch("7");

            verify(dispatcher, never()).dispatch(anyLong());
        }

        @Test
        @DisplayName("消息体非法时丢弃, 不触碰数据库")
        void dropsMalformedPayload() {
            consumer.handleDispatch("not-a-number");
            consumer.handleDispatch("");
            consumer.handleDispatch(null);

            verify(dispatchMapper, never()).claimDispatch(anyLong(), any());
            verify(dispatcher, never()).dispatch(anyLong());
        }

        @Test
        @DisplayName("分发异常时释放占用退回 PENDING, 交补扫重投")
        void releasesClaimOnUnexpectedFailure() {
            given(dispatchMapper.claimDispatch(7L, DispatchStatus.PENDING)).willReturn(1);
            doThrow(new IllegalStateException("db down")).when(dispatcher).dispatch(7L);

            consumer.handleDispatch("7");

            // 不抛异常: 避免 MQ 立即重试风暴, 由补扫按时间间隔重投
            verify(dispatchMapper).releaseDispatchClaim(7L);
        }
    }

    @Nested
    @DisplayName("补扫任务: 崩溃恢复")
    class RecoveryTests {

        /**
         * 模拟真实锁语义: setIfAbsent 写入随机 token, get 读回同一 token。
         * 只有 get 返回值与持有者 token 一致时才会删锁, 防止误删他人锁。
         */
        private void givenLockAcquired() {
            AtomicReference<String> holder = new AtomicReference<>();
            given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willAnswer(invocation -> {
                    holder.set(invocation.getArgument(1));
                    return true;
                });
            given(valueOps.get(anyString())).willAnswer(invocation -> holder.get());
        }

        @Test
        @DisplayName("拿不到分布式锁时跳过, 保证多副本不重复扫描")
        void skipsWhenLockHeldByOtherInstance() {
            given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(false);

            recoveryTask.recoverStaleDispatches();

            verify(dispatchMapper, never()).findStaleUndispatched(any(), anyInt());
            verify(redisTemplate, never()).delete(anyString());
        }

        @Test
        @DisplayName("PENDING 陈旧通知被重投到 MQ")
        void republishesStalePending() {
            givenLockAcquired();
            given(dispatchMapper.findStaleUndispatched(any(LocalDateTime.class), anyInt()))
                .willReturn(List.of(stale(1L, DispatchStatus.PENDING, 0)));
            given(dispatcher.exhausted(any())).willReturn(false);

            recoveryTask.recoverStaleDispatches();

            verify(rabbitTemplate).convertAndSend("notification.dispatch.queue", "1");
        }

        @Test
        @DisplayName("DISPATCHING 陈旧通知先释放占用再重投 (消费者中途崩溃)")
        void releasesStuckDispatchingBeforeRepublish() {
            givenLockAcquired();
            given(dispatchMapper.findStaleUndispatched(any(LocalDateTime.class), anyInt()))
                .willReturn(List.of(stale(2L, DispatchStatus.DISPATCHING, 1)));
            given(dispatcher.exhausted(any())).willReturn(false);

            recoveryTask.recoverStaleDispatches();

            // 先把 DISPATCHING 退回 PENDING, 否则消费者下次 CAS 会因状态不符而拒绝
            verify(dispatchMapper).releaseDispatchClaim(2L);
            verify(rabbitTemplate).convertAndSend("notification.dispatch.queue", "2");
        }

        @Test
        @DisplayName("重试次数耗尽则标记失败, 不再重投")
        void abandonsWhenAttemptsExhausted() {
            givenLockAcquired();
            Notification exhausted = stale(3L, DispatchStatus.PENDING, NotificationDispatcher.MAX_ATTEMPTS);
            given(dispatchMapper.findStaleUndispatched(any(LocalDateTime.class), anyInt()))
                .willReturn(List.of(exhausted));
            given(dispatcher.exhausted(exhausted)).willReturn(true);

            recoveryTask.recoverStaleDispatches();

            verify(dispatchMapper).markDispatchFailed(3L);
            verify(rabbitTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("无陈旧通知时不重投, 正常释放锁")
        void doesNothingWhenNoStaleRows() {
            givenLockAcquired();
            given(dispatchMapper.findStaleUndispatched(any(LocalDateTime.class), anyInt()))
                .willReturn(List.of());

            recoveryTask.recoverStaleDispatches();

            verify(rabbitTemplate, never()).convertAndSend(anyString(), any(Object.class));
            verify(redisTemplate).delete(anyString());
        }

        @Test
        @DisplayName("单条重投失败不影响其余通知")
        void continuesWhenSingleRepublishFails() {
            givenLockAcquired();
            given(dispatchMapper.findStaleUndispatched(any(LocalDateTime.class), anyInt()))
                .willReturn(List.of(stale(4L, DispatchStatus.PENDING, 0), stale(5L, DispatchStatus.PENDING, 0)));
            given(dispatcher.exhausted(any())).willReturn(false);
            doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).convertAndSend("notification.dispatch.queue", "4");

            recoveryTask.recoverStaleDispatches();

            ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
            verify(rabbitTemplate, atLeastOnce())
                .convertAndSend(eq("notification.dispatch.queue"), ids.capture());
            // 两条都尝试过: 第一条失败不阻断第二条
            assertThat(ids.getAllValues()).contains("4", "5");
        }

        @Test
        @DisplayName("锁已被他人接管时不删锁, 避免误删他人锁")
        void doesNotDeleteLockOwnedByOther() {
            given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(true);
            // 锁在扫描期间被另一个实例接管并续期, 读回的 token 已不是自己的
            given(valueOps.get(anyString())).willReturn("other-instance-token");
            given(dispatchMapper.findStaleUndispatched(any(LocalDateTime.class), anyInt()))
                .willReturn(List.of());

            recoveryTask.recoverStaleDispatches();

            verify(redisTemplate, never()).delete(anyString());
        }
    }

    private Notification stale(Long id, DispatchStatus status, int attempts) {
        Notification n = new Notification();
        n.setId(id);
        n.setUserId(1L);
        n.setTitle("t");
        n.setContent("c");
        n.setDispatchStatus(status);
        n.setDispatchAttempts(attempts);
        n.setCreateTime(LocalDateTime.now().minusHours(1));
        return n;
    }
}
