package com.cloudinfra.notification.service;

import com.cloudinfra.notification.ws.NotificationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 实时推送出口。
 *
 * <p>{@code NotificationServiceImpl} 此前用
 * {@code import static com.cloudinfra.notification.ws.NotificationWebSocketHandler.pushToUser}
 * 直接静态调用 WebSocket 处理器 —— 隐藏依赖, 无法替换/打桩, 单测必须依赖其静态
 * {@code ConcurrentHashMap}。经此 bean 后推送成为可注入依赖, 服务层也不再直接
 * 依赖 ws 包的静态状态。
 */
@Component
@RequiredArgsConstructor
public class NotificationPushGateway {

    private final NotificationWebSocketHandler handler;

    /** 推送失败只记录, 不影响调用方主流程 —— 与原静态调用的语义保持一致。 */
    public void pushToUser(Long userId, Object payload) {
        handler.push(userId, payload);
    }
}
