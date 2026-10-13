package com.cloudinfra.system.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 审计日志专用异步执行器。
 *
 * <p>默认的 {@code applicationTaskExecutor} 是 {@code newVirtualThreadPerTaskExecutor()}，
 * 对并发数无上界。审计日志的每个任务都要占用一次数据库连接（{@code operLogMapper.insert}），
 * 因此真正的瓶颈是 HikariCP 连接池而非线程数——无界虚拟线程会让等待连接的线程无限堆积，
 * 表现为内存缓慢增长而非立即失败。
 *
 * <p>这里改用固定大小线程池 + 有界队列 + {@link ThreadPoolExecutor.CallerRunsPolicy}：
 * 队列满时由发布线程自己执行写入，把压力回传给请求线程，从而形成背压而不是丢弃审计数据。
 */
@Configuration
public class AuditLogExecutorConfig {

    @Bean("auditLogTaskExecutor")
    public AsyncTaskExecutor auditLogTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("audit-log-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return new TaskExecutorAdapter(executor);
    }
}