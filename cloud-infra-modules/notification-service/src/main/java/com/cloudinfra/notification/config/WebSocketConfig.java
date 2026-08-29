package com.cloudinfra.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 配置: 注册 JSR-356 ServerEndpointExporter (扫描 @ServerEndpoint bean)
 * 详见 NotificationWebSocketHandler @ServerEndpoint("/notification/ws/{userId}")
 *
 * <p>Spring Boot 4 web 启动时自动 scan @ServerEndpoint, 显式注册 bean
 * 是为了显式可控 (例如在 @Profile("!test") 排除测试场景).</p>
 */
@Configuration
public class WebSocketConfig {

    /**
     * 显式注册 ServerEndpointExporter 扫描 @ServerEndpoint 注解的 bean
     */
    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }

    /**
     * WebSocket 容器配置: 文本消息最大 64KB, 空闲超时 30 分钟
     * 避免单个 session 内存爆炸或长时间空挂
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxSessionIdleTimeout(30 * 60 * 1000L);
        return container;
    }
}
