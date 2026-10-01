package com.cloudinfrait.scheduling;

import com.cloudinfra.framework.tracing.TraceUtils;
import com.cloudinfra.system.domain.SysTxMessage;
import com.cloudinfra.system.service.SysTxMessageService;
import com.cloudinfra.system.task.TxMessageRetryTask;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行时观测: 真实定时任务在真实调度配置下<b>真的被触发</b>, 且真的在执行业务动作。
 *
 * <h2>为什么需要这个 IT（与已有守护的区别）</h2>
 *
 * <p>{@code SchedulingEnabledTest} 覆盖了两件事, 但都不是本 IT 要证明的:</p>
 * <ul>
 *   <li>{@code applicationEnablesScheduling} —— 断言 {@code SystemApplication} 类<b>带着</b>
 *       {@code @EnableScheduling} 注解。这是<b>读注解</b>, 属检查而非观测。</li>
 *   <li>{@code enableSchedulingActuallyTriggersTasks} —— 用一个<b>自造的 {@code Probe}</b>
 *       证明 Spring 调度机制本身可用。Probe 与真实业务任务无关:
 *       真实任务若因扫描/依赖/代理问题没被注册, 它照样全绿。</li>
 * </ul>
 *
 * <p>本 IT 补的正是那个缺口: 用<b>真实的 {@link TxMessageRetryTask}</b>、走<b>真实的
 * {@code @ComponentScan}</b> 与 {@code @EnableScheduling}, 然后观测两件事:</p>
 * <ol>
 *   <li>该任务被调度器触发（而非被别处直调）</li>
 *   <li>它<b>真的调用了 {@code findPendingMessages()}</b> —— 不是"空跑"，确实进入了业务逻辑</li>
 * </ol>
 *
 * <p>第 2 点才是关键: 2026-09-30 的审计结论是"4 个 {@code @Scheduled} 任务从未执行过"。
 * 只证明"方法被调用"仍可能是被别处直调; 必须证明<b>它自己走向了数据源</b>。
 * 该任务是 ROADMAP 上标 🟢 的"事务消息重投 / DLQ 重试"能力的唯一实现。</p>
 *
 * <p>协作的三方依赖用 mock, 但<b>调用是否发生</b>就是被断言的观测对象 ——
 * mock 本身不是观测替代品, 这里断言的是"真实任务主动发起了这次调用"。</p>
 *
 * <p>{@code @Scheduled(fixedDelay = 60000)} 无 initialDelay, 首次执行立即发生, 无需等 60 秒。</p>
 *
 * <h2>为什么本类不在 com.cloudinfra 包下</h2>
 *
 * <p><b>2026-10-01 真实踩到的事故</b>: 本类最初放在 {@code com.cloudinfra.system}。
 * 它内嵌的 {@code @Configuration} 静态夹具类会被 {@code SystemApplication} 的
 * {@code @ComponentScan(basePackages = "com.cloudinfra")} 扫到 ——
 * {@code ClassPathScanningCandidateComponentProvider} 默认
 * {@code considerIndependentNestedClasses = true}, 静态嵌套的 {@code @Configuration} 会被发现。
 * 结果是那些夹具在<b>其他测试的 Spring 上下文里也生效</b>, 注册出一个
 * {@code SysTxMessageService} 的 mock, 令 {@code TxMessageFlowIntegrationTest}
 * 的 {@code TxMessageRetryTask} 拿到空列表而不真正查库 → 该测试稳定失败。</p>
 *
 * <p>换个包即可彻底避开: {@code com.cloudinfra} 不匹配 {@code com.cloudinfrait}。</p>
 *
 * <p><b>推论</b>: 任何在 {@code com.cloudinfra} 下的测试夹具都不能带 {@code @Configuration} /
 * {@code @Component}, 否则会变成跨测试的全局污染 —— 且这类污染只在<b>整套 IT 一起跑</b>时
 * 显形, 单独跑该测试永远是绿的。详见 AGENTS.md 第三条硬规则。</p>
 */
class ScheduledTaskRuntimeTriggerIT {

    /** 真实任务真的去取待发消息的次数 */
    private static final AtomicInteger FETCH_CALLS = new AtomicInteger();

    /**
     * 当前用例的观测闸门。
     *
     * <p><b>用普通 volatile 字段而非 ThreadLocal</b>: 任务跑在调度器线程上,
     * 而用例在测试线程 —— ThreadLocal 不跨线程, 那样写会让 await 永远超时。
     * 这是本测试第一版真实踩到的坑。</p>
     */
    private static volatile CountDownLatch gate;

    @BeforeEach
    void resetObservations() {
        FETCH_CALLS.set(0);
        gate = new CountDownLatch(1);
    }

    @Test
    @DisplayName("真实 TxMessageRetryTask 被调度触发, 且真的取到了待发消息")
    void realScheduledTaskIsTriggeredAndDoesRealWork() throws Exception {
        try (AnnotationConfigApplicationContext ctx =
                 new AnnotationConfigApplicationContext(TaskHarness.class)) {

            assertThat(gate.await(30, TimeUnit.SECONDS))
                .as("真实 TxMessageRetryTask 未在 30s 内被 @Scheduled 触发。"
                    + "若此处超时, 说明调度链路在真实任务上并未生效 —— "
                    + "而不是某个探针失效 (SchedulingEnabledTest 的 Probe 仍可能全绿)")
                .isTrue();

            assertThat(FETCH_CALLS.get())
                .as("任务被触发了却没调用 findPendingMessages, 说明它空跑而非执行业务")
                .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("去掉 @EnableScheduling 后同一任务不再触发（负向对照）")
    void withoutEnableSchedulingTaskNeverFires() throws Exception {
        // 同一个真实任务、同一个 @ComponentScan, 唯一差别是摘掉 @EnableScheduling
        try (AnnotationConfigApplicationContext ctx =
                 new AnnotationConfigApplicationContext(HarnessWithoutScheduling.class)) {

            assertThat(gate.await(5, TimeUnit.SECONDS))
                .as("负向对照失败: 移除 @EnableScheduling 后任务仍被触发, "
                    + "说明触发并非来自调度器 —— 本 IT 证明的不是想证明的那件事")
                .isFalse();
            assertThat(FETCH_CALLS.get()).isZero();
        }
    }

    // ========== 夹具 ==========

    /** 带调度的夹具: 真实任务类 + 真实扫描 + mock 协作者 */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ComponentScan(
        basePackages = "com.cloudinfra.system.task",
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
            classes = TxMessageRetryTask.class)
    )
    static class TaskHarness {

        @Bean
        SysTxMessageService txMessageService() {
            return observingTxMessageService();
        }

        @Bean
        StreamBridge streamBridge() {
            return mock(StreamBridge.class);
        }

        @Bean
        TraceUtils traceUtils() {
            return realTraceUtils();
        }
    }

    /** 负向对照夹具: 仅去掉 @EnableScheduling, 其余与上面完全一致 */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
        basePackages = "com.cloudinfra.system.task",
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
            classes = TxMessageRetryTask.class)
    )
    static class HarnessWithoutScheduling {

        @Bean
        SysTxMessageService txMessageService() {
            return observingTxMessageService();
        }

        @Bean
        StreamBridge streamBridge() {
            return mock(StreamBridge.class);
        }

        @Bean
        TraceUtils traceUtils() {
            return realTraceUtils();
        }
    }

    /**
     * 返回一个会在被调用时打开观测闸门的 Service mock。
     *
     * <p>任务拿到非空列表后会继续走发送逻辑, 故这里让 StreamBridge 正常返回。</p>
     */
    private static SysTxMessageService observingTxMessageService() {
        SysTxMessageService service = mock(SysTxMessageService.class);
        SysTxMessage message = new SysTxMessage();
        message.setId(1L);
        message.setTopic("test-topic");
        message.setContent("{}");
        message.setRetryCount(0);
        doAnswer(inv -> {
            FETCH_CALLS.incrementAndGet();
            CountDownLatch current = gate;
            if (current != null) {
                current.countDown();
            }
            return List.of(message);
        }).when(service).findPendingMessages();
        return service;
    }

    /**
     * 真实的 {@link TraceUtils}（NOOP tracer），而不是 mock。
     *
     * <p>选真实而非 mock 的理由: 任务体整个包在 {@code createCustomSpan(name, Supplier)} 里。
     * 若把 TraceUtils 也 mock 掉, 很容易写出一个"吞掉 supplier"的桩 ——
     * 那时任务体从未执行, 我们观测到的"未触发"就成了 mock 造成的假象,
     * 本 IT 会退化成自证的空壳。真实实现会真的调用 {@code supplier.get()}。</p>
     *
     * <p>{@code TraceUtils} 在无 Tracer bean 时回落到 {@code Tracer.NOOP}, 不影响执行。</p>
     */
    @SuppressWarnings("unchecked")
    private static TraceUtils realTraceUtils() {
        ObjectProvider<Tracer> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        return new TraceUtils(emptyProvider);
    }
}