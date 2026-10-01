package com.cloudinfra.notification.ws;

import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 守护 WebSocket 发送的**并发安全**（2026-10-01）。
 *
 * <h2>为什么需要</h2>
 *
 * <p>2026-10-01 CI 上 {@code WebSocketIntegrationTest} 偶发失败，根因是：</p>
 * <pre>
 *   java.lang.IllegalStateException: The remote endpoint was in state
 *   [TEXT_FULL_WRITING] which is an invalid state for called method
 *     at WsRemoteEndpointImplBase$StateMachine.textStart
 *     at NotificationWebSocketHandler.sendText
 * </pre>
 *
 * <p>{@code getBasicRemote()} 返回的是**阻塞式且非线程安全**的端点：Tomcat 用状态机保护它，
 * 同一连接上只要有第二个线程同时进入就会抛该异常。而本 handler 的发送确实来自两个线程：</p>
 * <ul>
 *   <li>心跳 {@code pong}（{@code @OnMessage}，跑在 Tomcat 的消息线程上）</li>
 *   <li>业务推送 {@code pushToUser}（跑在 MQ 消费线程上）</li>
 * </ul>
 *
 * <p>它本地几乎必绿、只在 CI 的时序下偶发，属于最难归因的一类；因此本测试用
 * <b>受控的假端点</b>把并发窗口固定下来，让"有没有发生并发进入"变成确定性断言。</p>
 *
 * <p>第二个用例是<b>负向对照</b>：它绕过 {@code sendText} 直接并发调用
 * {@code getBasicRemote().sendText}，等价于"没有互斥"的旧实现。
 * 若它检测不到并发进入，说明探针本身无效，第一个用例的通过就不能证明互斥生效 ——
 * 这一点在本次开发中确实发生过一次（初版 catch 范围太窄导致负向对照以 ERROR 收场，
 * 反而从侧面证明并发进入真的发生了）。</p>
 */
class NotificationWebSocketSendConcurrencyTest {

    private static final int CONCURRENCY = 8;

    /**
     * 模拟 Tomcat 的状态机：同一端点上若已有线程在发送，再进入就抛
     * {@code TEXT_FULL_WRITING}。
     */
    @SuppressWarnings("unchecked")
    private static Session sessionDetectingConcurrentSend() throws IOException {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger concurrentEntries = new AtomicInteger();

        RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
        org.mockito.Mockito.doAnswer(inv -> {
            if (inFlight.incrementAndGet() > 1) {
                concurrentEntries.incrementAndGet();
                inFlight.decrementAndGet();
                throw new IllegalStateException(
                    "The remote endpoint was in state [TEXT_FULL_WRITING] which is an invalid state");
            }
            try {
                // 拉长临界区, 让并发窗口必然出现
                Thread.sleep(20);
                return null;
            } finally {
                inFlight.decrementAndGet();
            }
        }).when(remote).sendText(anyString());

        Session session = mock(Session.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("session-under-test");
        when(session.getBasicRemote()).thenReturn(remote);

        // 把并发进入次数挂在 mock 上供断言读取
        CONCURRENT_ENTRIES.set(concurrentEntries);
        return session;
    }

    private static final AtomicReference<AtomicInteger> CONCURRENT_ENTRIES = new AtomicReference<>();

    @Test
    @DisplayName("并发向同一连接发送: 不发生 TEXT_FULL_WRITING（每连接串行化生效）")
    void concurrentSendsToSameSessionAreSerialized() throws Exception {
        Session session = sessionDetectingConcurrentSend();
        Method sendText = NotificationWebSocketHandler.class
            .getDeclaredMethod("sendText", Session.class, String.class);
        sendText.setAccessible(true);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < CONCURRENCY; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    return (Boolean) sendText.invoke(null, session, "{\"n\":" + idx + "}");
                }));
            }
            startTogether.countDown();

            List<Boolean> results = new ArrayList<>();
            for (var f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }

            assertThat(CONCURRENT_ENTRIES.get().get())
                .as("同一连接上出现了并发进入 getBasicRemote().sendText —— "
                    + "session 级互斥缺失, 生产上会抛 TEXT_FULL_WRITING")
                .isZero();
            assertThat(results)
                .as("所有并发发送都应成功")
                .allMatch(Boolean.TRUE::equals);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("负向对照: 去掉互斥后同一场景必然出现并发进入（证明本测试确实在守护）")
    void withoutMutexConcurrentEntriesAreDetected() throws Exception {
        Session session = sessionDetectingConcurrentSend();
        Method sendText = NotificationWebSocketHandler.class
            .getDeclaredMethod("sendText", Session.class, String.class);
        sendText.setAccessible(true);

        // 绕过 sendText, 直接并发调用 getBasicRemote().sendText —— 等价于"没有互斥"的旧实现
        RemoteEndpoint.Basic raw = session.getBasicRemote();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < CONCURRENCY; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    try {
                        raw.sendText("{\"n\":" + idx + "}");
                    } catch (IOException | IllegalStateException expected) {
                        // 预期内: 假端点模拟 Tomcat 状态机, 并发进入即抛 TEXT_FULL_WRITING
                    }
                    return null;
                }));
            }
            startTogether.countDown();
            for (var f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }

            assertThat(CONCURRENT_ENTRIES.get().get())
                .as("负向对照失败: 不加互斥时也检测不到并发进入 —— "
                    + "说明本测试的探针本身无效, 上一个用例的通过不能证明互斥生效")
                .isPositive();
        } finally {
            pool.shutdownNow();
        }
    }
}