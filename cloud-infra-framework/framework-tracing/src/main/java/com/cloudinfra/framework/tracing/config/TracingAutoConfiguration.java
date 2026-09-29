package com.cloudinfra.framework.tracing.config;

import com.cloudinfra.common.context.MDCContextTaskDecorator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 链路追踪自动配置类
 * 提供基于虚拟线程的任务执行器（MDC 上下文传播），Tracer 由 micrometer 自动配置提供。
 * 与 framework-web 的 VirtualThreadConfig 保持一致的虚拟线程策略（ADR-001），
 * 避免平台线程池在虚拟线程环境下的无谓占用与有界队列拥塞。
 */
@AutoConfiguration
public class TracingAutoConfiguration {

    @AutoConfiguration
    @ConditionalOnProperty(prefix = "spring.threads.virtual", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class VirtualThreadExecutorConfiguration {

        private final MDCContextTaskDecorator mdcDecorator = new MDCContextTaskDecorator();

        /**
         * 虚拟线程执行器 + MDC 上下文传播
         * 供显式注入 {@link Executor} 的异步代码使用；@Async 默认走 applicationTaskExecutor。
         */
        @Bean
        public Executor taskExecutor() {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            return new TaskExecutorAdapter(task -> executor.execute(mdcDecorator.decorate(task)));
        }
    }
}
