package com.cloudinfra.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 通知渠道分发线程池配置
 * <p>
 * 使用共享的虚拟线程执行器替代每次分发新建线程池：
 * - 虚拟线程按需创建、廉价挂起，支持海量并发 IO 渠道分发；
 * - 单一共享执行器避免每次发送都创建/销毁执行器的开销与资源抖动；
 * - 应用关闭时由 JVM 虚拟线程调度器自动回收。
 */
@Configuration
public class DispatchExecutorConfig {

    @Bean(name = "notificationDispatchExecutor", destroyMethod = "close")
    public ExecutorService notificationDispatchExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}