package com.cloudinfra.notification.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;

/**
 * WebSocket Metrics 桥接器
 *
 * <p>JSR-356 {@code @ServerEndpoint} 由 Jakarta WebSocket 容器 new 实例,
 * 绕过 Spring 依赖注入, 无法直接用 {@code @Autowired} 拿 MeterRegistry.
 * 本类用 {@code @PostConstruct} 在 Spring 启动时把全局唯一 MeterRegistry
 * 注入静态字段, 供 JSR-356 实例 (NotificationWebSocketHandler) 读取.</p>
 */
@Configuration
public class MetricsBinder {

    private static volatile MeterRegistry REGISTRY;

    @Autowired
    private MeterRegistry meterRegistry;

    @PostConstruct
    public void init() {
        REGISTRY = meterRegistry;
    }

    /**
     * 给 JSR-356 handler 用的静态 accessor
     */
    public static MeterRegistry getRegistry() {
        return REGISTRY;
    }
}
