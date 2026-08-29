package com.cloudinfra.notification.config;

import com.cloudinfra.notification.ws.NotificationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 实时通知推送配置 (原始 WebSocket, 不依赖 STOMP)
 *
 * <p>设计: 按 userId 订阅, server 主动 push 通知到客户端
 * <ul>
 *   <li>端点: ws://host:8084/notification/ws/{userId}
 *   <li>客户端连上后自动收到该 userId 的所有通知
 *   <li>鉴权: 通过 X-User-Id header (网关已注入) / 直接路径传 userId
 *   <li>心跳: 默认 30s, 客户端应定期 ping
 * </ul>
 *
 * <p>为什么不用 STOMP: 业务只需 server -> client 单向推送, 原始 WebSocket
 * 协议更轻, 客户端用浏览器原生 WebSocket API 即可对接.</p>
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final NotificationWebSocketHandler notificationWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notificationWebSocketHandler, "/notification/ws/{userId}")
            .setAllowedOriginPatterns("*");
    }
}
