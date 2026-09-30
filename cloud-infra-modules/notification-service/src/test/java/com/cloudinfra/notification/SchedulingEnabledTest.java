package com.cloudinfra.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

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
 * 方法静默不执行。而当时 11 个补扫测试全部用 mock 直接调用方法、全部通过 ——
 * 正是 AGENTS.md 所说"组件单元测试不是完成依据"的典型案例:
 * {@code NotificationDispatchRecoveryTask} (M7-08 崩溃恢复兜底) 与
 * {@code NotificationScheduler} (定时通知引擎) 被标为 🟢, 线上却一次都没跑过。</p>
 *
 * <p>本测试不依赖数据库/Redis, 可在每次 {@code mvn test} 中执行。</p>
 */
class SchedulingEnabledTest {

    /** 被守护的定时任务, 让覆盖面在任务增减时一目了然 */
    private static final Set<String> EXPECTED_TASKS = Set.of(
        "com.cloudinfra.notification.service.NotificationScheduler#processScheduledNotifications",
        "com.cloudinfra.notification.service.NotificationDispatchRecoveryTask#recoverStaleDispatches"
    );

    @Test
    @DisplayName("通知服务存在 @Scheduled 任务 (扫描非空, 避免守护静默失效)")
    void scheduledTasksAreDiscoverable() throws Exception {
        assertThat(findScheduledTasks("com.cloudinfra.notification"))
            .as("通知服务的 @Scheduled 任务清单")
            .containsAll(EXPECTED_TASKS);
    }

    @Test
    @DisplayName("NotificationApplication 带 @EnableScheduling")
    void applicationEnablesScheduling() {
        assertThat(NotificationApplication.class.getAnnotation(EnableScheduling.class))
            .as("NotificationApplication 缺少 @EnableScheduling, 上述任务不会触发, "
                + "崩溃滞留的 DISPATCHING 行将永久卡住")
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
