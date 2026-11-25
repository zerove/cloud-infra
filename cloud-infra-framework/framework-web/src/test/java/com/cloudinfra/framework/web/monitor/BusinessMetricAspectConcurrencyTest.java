package com.cloudinfra.framework.web.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BusinessMetricAspect} Counter 缓存的并发守护。
 *
 * <p>缺陷背景: 缓存原为普通 {@code HashMap}, 而 {@code increment} 由
 * {@code @Around}(返回路径)与 {@code @AfterThrowing}(异常路径)两条并发路径调用。
 * 普通 HashMap 的并发 {@code computeIfAbsent} 有两个后果:
 * <ol>
 *   <li>抛 {@code ConcurrentModificationException} —— 会把业务异常路径上的
 *       指标记录失败变成一次额外的 500</li>
 *   <li>resize 期间写入丢失 —— <b>不报错</b>, 但计数偏小, 告警可能漏报</li>
 * </ol>
 * 第 2 条是本测试的重点: 它是静默失败, 只断言"不抛异常"的测试抓不到。
 *
 * <p>因此本测试断言的是<strong>计数守恒</strong>而非仅仅"没抛异常":
 * 并发 N 次调用后, registry 里的 Counter 总数必须精确等于 N。
 *
 * <p>负向对照: 把 {@code counterCache} 改回 {@code new HashMap<>()} 后,
 * {@link #concurrentIncrementsMustNotLoseCounts()} 必须失败。
 */
class BusinessMetricAspectConcurrencyTest {

    private SimpleMeterRegistry registry;
    private BusinessMetricAspect aspect;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        aspect = new BusinessMetricAspect(registry);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Counter> cache() throws Exception {
        Field f = BusinessMetricAspect.class.getDeclaredField("counterCache");
        f.setAccessible(true);
        return (Map<String, Counter>) f.get(aspect);
    }

    private void invokeIncrement(String metric, String tagKey, String tagValue) throws Exception {
        Method m = BusinessMetricAspect.class.getDeclaredMethod(
                "increment", String.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(aspect, metric, tagKey, tagValue);
    }

    /**
     * 核心守护: 并发递增后计数必须守恒。
     *
     * <p>用同一个 key(单线程也会命中缓存)与多个 key 两种形态:
     * 前者检验同一 key 的并发 computeIfAbsent, 后者检验扩容期的写入丢失。
     */
    @Test
    @DisplayName("并发递增后 Counter 计数守恒, 且缓存容器线程安全")
    void concurrentIncrementsMustNotLoseCounts() throws Exception {
        assertTrue(
                cache() instanceof java.util.concurrent.ConcurrentHashMap,
                "counterCache 必须是 ConcurrentHashMap; 实际是 "
                        + cache().getClass().getName()
                        + "。普通 HashMap 在并发 computeIfAbsent 下会 CME 或丢计数。");

        final int threads = 16;
        final int perThread = 500;
        final int total = threads * perThread;

        // 形态 A: 全部打同一个 key —— 检验同一 key 的并发注册
        runConcurrently(threads, perThread, i -> invokeIncrement("m.same", "result", "success"));

        Counter same = registry.find("m.same").tag("result", "success").counter();
        assertEquals((double) total, same.count(),
                "同一 key 并发 " + total + " 次, 计数必须精确等于 " + total
                        + "(丢计数是静默失败, 只断言不抛异常抓不到)");

        // 形态 B: 每个线程打不同 key —— 检验扩容期间的写入丢失
        runConcurrently(threads, perThread, i -> {
            try {
                invokeIncrement("m.many", "reason", "r" + i);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        int sum = 0;
        for (Counter c : registry.find("m.many").counters()) {
            sum += (int) c.count();
        }
        assertEquals((double) total, (double) sum,
                "多 key 并发 " + total + " 次, 总计数必须守恒");
        assertEquals(threads, registry.find("m.many").counters().size(),
                "每个线程应各自产生一个 Counter");
    }

    /**
     * 第二个守护: 并发调用中任何一个线程失败都要被发现, 不能被静默吞掉。
     *
     * <p>{@code invokeIncrement} 走反射, 异常会以 InvocationTargetException 传出。
     * 这里显式收集, 避免 {@code CompletableFuture} 默认吞掉异常。
     */
    @Test
    @DisplayName("并发调用中的异常必须被观察到, 不得被 Future 静默吞掉")
    void concurrentFailuresMustSurface() throws Exception {
        final int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    invokeIncrement("m.err", "reason", "x");
                } catch (Throwable t) {
                    failures.incrementAndGet();
                    firstError.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务未在 30s 内完成");
        pool.shutdownNow();

        assertEquals(0, failures.get(),
                "并发递增不应失败, 但有 " + failures.get() + " 次: " + firstError.get());
    }

    private interface Task { void run(int index) throws Exception; }

    /** 用 CountDownLatch 让所有线程在同一时刻起跑 —— 否则并发窗口太窄, 负向对照会假绿。 */
    private void runConcurrently(int threads, int perThread, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int n = 0; n < perThread; n++) {
                        task.run(idx);
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务未在 60s 内完成");
        pool.shutdownNow();
        if (error.get() != null) {
            throw new IllegalStateException("并发执行出现异常", error.get());
        }
    }
}