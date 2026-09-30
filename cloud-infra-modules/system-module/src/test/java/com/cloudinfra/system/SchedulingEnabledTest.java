package com.cloudinfra.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 {@code @Scheduled} 任务真正被调度。
 *
 * <p><b>为什么需要这个测试</b>: 2026-09-30 审计发现全仓无任何 {@code @EnableScheduling}。
 * Spring Boot 的 {@code TaskSchedulingAutoConfiguration} 只提供 {@code TaskScheduler} Bean,
 * <b>不会</b>注册 {@code ScheduledAnnotationBeanPostProcessor}, 因此所有 {@code @Scheduled}
 * 方法静默不执行。而相关组件自测全绿 —— 正是 AGENTS.md 所说"组件单元测试不是完成依据"的典型案例:
 * {@code TxMessageRetryTask} (事务消息重投, ROADMAP 标为 🟢 DLQ 重试) 与
 * {@code HealthWatcher} (5 分钟健康巡检与自动告警闭环) 被标为已交付, 线上却一次都没跑过。</p>
 *
 * <p>本测试不依赖数据库/Redis, 可在每次 {@code mvn test} 中执行。</p>
 */
class SchedulingEnabledTest {

    /** 被守护的定时任务, 让覆盖面在任务增减时一目了然 */
    private static final Set<String> EXPECTED_TASKS = Set.of(
        "com.cloudinfra.system.service.HealthWatcher#watch",
        "com.cloudinfra.system.task.TxMessageRetryTask#retryPendingMessages"
    );

    @Test
    @DisplayName("系统模块存在 @Scheduled 任务 (扫描非空, 避免守护静默失效)")
    void scheduledTasksAreDiscoverable() throws Exception {
        assertThat(findScheduledTasks("com.cloudinfra.system"))
            .as("系统模块的 @Scheduled 任务清单")
            .containsAll(EXPECTED_TASKS);
    }

    @Test
    @DisplayName("SystemApplication 带 @EnableScheduling")
    void applicationEnablesScheduling() {
        assertThat(SystemApplication.class.getAnnotation(EnableScheduling.class))
            .as("SystemApplication 缺少 @EnableScheduling, 事务消息重投与健康巡检不会触发, "
                + "但组件自测全绿, 属于静默失效")
            .isNotNull();
    }

    @Test
    @DisplayName("@EnableScheduling 在当前 Spring 版本确实生效 (机制实证)")
    void enableSchedulingActuallyTriggersTasks() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        Probe.fired = fired;
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Probe.class)) {
            assertThat(fired.await(10, TimeUnit.SECONDS))
                .as("@EnableScheduling 未生效: @Scheduled 方法在 10s 内未被触发")
                .isTrue();
        }
    }

    @Configuration
    @EnableScheduling
    static class Probe {
        static volatile CountDownLatch fired = new CountDownLatch(1);

        @Scheduled(fixedDelay = 50)
        void tick() {
            fired.countDown();
        }
    }

    /** 扫描指定包下所有 {@code @Component} 类, 收集带 {@code @Scheduled} 的方法 */
    private static Set<String> findScheduledTasks(String basePackage) throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        Set<String> result = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) {
                    result.add(type.getName() + "#" + method.getName());
                }
            }
        }
        return result;
    }
}
