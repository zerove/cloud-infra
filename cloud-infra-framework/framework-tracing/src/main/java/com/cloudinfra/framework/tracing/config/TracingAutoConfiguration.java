package com.cloudinfra.framework.tracing.config;

import com.cloudinfra.common.context.MDCContextTaskDecorator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 链路追踪自动配置类
 * 提供任务执行器（MDC 上下文传播），Tracer 由 micrometer 自动配置提供
 */
@AutoConfiguration
public class TracingAutoConfiguration {

    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(25);
        executor.setTaskDecorator(new MDCContextTaskDecorator());
        executor.initialize();
        return executor;
    }
}
