package com.cloudinfra.notification.config;

import com.cloudinfra.notification.service.NotificationDispatchRecoveryTask;
import com.cloudinfra.notification.service.NotificationDispatcher;
import com.cloudinfra.notification.service.impl.NotificationServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 通知模块的执行器配置。
 *
 * <p>使用共享的虚拟线程执行器替代每次分发新建线程池:
 * <ul>
 *   <li>虚拟线程按需创建、廉价挂起, 支持海量并发 IO 渠道分发;</li>
 *   <li>单一共享执行器避免每次发送都创建/销毁执行器的开销与资源抖动;</li>
 *   <li>应用关闭时由 JVM 虚拟线程调度器自动回收。</li>
 * </ul>
 *
 * <p>⚠️【2026-10-07】本类在一天内被改了三次才正确, 过程记录如下,
 * 因为每次失败都指向同一个认知错误: <b>试图与 Spring Boot 争抢
 * {@code applicationTaskExecutor} 这个名字</b>。
 *
 * <p><b>缺陷本身</b>: Boot 的
 * {@code TaskExecutorConfigurations.TaskExecutorConfiguration} 挂着
 * {@code @ConditionalOnMissingBean(java.util.concurrent.Executor.class)},
 * 而 {@code ExecutorService extends Executor} —— 只要本类注册了任何
 * {@code Executor}, Boot 就不再建 {@code applicationTaskExecutor}。
 * 而 {@link NotificationServiceImpl} 与
 * {@link NotificationDispatchRecoveryTask} 都按名注入它, 于是启动失败:
 * <pre>
 * NoSuchBeanDefinitionException: No qualifying bean of type
 *   'org.springframework.core.task.AsyncTaskExecutor' available
 * </pre>
 * → 整个 Spring 上下文加载失败 → notification 集成测试 12 项全红。
 *
 * <p><b>错误修法 1</b>: 把返回类型从 {@code ExecutorService} 收窄为
 * {@code Executor}, 以为能避开条件。实测<b>多出一个</b>错误:
 * {@code No qualifying bean of type 'java.util.concurrent.ExecutorService'}
 * —— {@link NotificationDispatcher} 按类型注入, 类型被改窄后匹配不上。
 *
 * <p><b>错误修法 2</b>: 自己定义一个叫 {@code applicationTaskExecutor} 的 bean。
 * 结果报 {@code BeanDefinitionOverrideException} —— 因为 Boot 到底建不建
 * 那个 bean, 取决于条件评估发生在本类<b>之前</b>还是<b>之后</b>(即 bean
 * 注册顺序), 测试环境默认不允许覆盖。两种顺序我都实测到了:
 * <ul>
 *   <li>本类先 → Boot 判定「已有 Executor」→ 不建 → 注入方报找不到;</li>
 *   <li>Boot 先 → Boot 建 → 与本类同名 → 覆盖冲突。</li>
 * </ul>
 * <b>同名 = 抢定义权, 顺序无论如何都会在一侧失败。</b>
 *
 * <p><b>正确解法</b>: 用<b>自有名字</b> {@code notificationAsyncExecutor},
 * 把三个注入点统一改为按名引用。这样本模块的依赖只由本模块决定,
 * 不受其他模块注册了什么、顺序如何影响。
 *
 * <p>另: 中途曾把它拆成 {@code notificationDispatchExecutor} +
 * {@code notificationAsyncExecutor} 两个同类型 bean, 结果
 * {@link NotificationDispatcher} 按类型注入时报
 * {@code NoUniqueBeanDefinitionException} —— 故最终<b>只保留一个</b>。
 *
 * <p>受控实验依据: 给测试上下文加
 * {@code spring.task.execution.mode=force}(强制 Boot 注册 executor)后,
 * 原错误消失、失败点前移到无关的 security 配置 —— 证明缺的确实是
 * 一个 {@code AsyncTaskExecutor}, 与 Boot 是否参与无关。
 */
@Configuration
public class DispatchExecutorConfig {

    /**
     * 本模块唯一的执行器: 虚拟线程按任务创建, 无上限, 关闭时自动回收。
     *
     * <p>返回类型刻意保持 {@link ExecutorService}(比 {@code Executor} 窄):
     * 三个注入点按名引用它, 而 {@code ExecutorService} 已覆盖它们所需的
     * {@code execute(Runnable)}。
     *
     * @see NotificationDispatcher
     * @see NotificationServiceImpl
     * @see NotificationDispatchRecoveryTask
     */
    @Bean(name = "notificationAsyncExecutor", destroyMethod = "close")
    public ExecutorService notificationAsyncExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}