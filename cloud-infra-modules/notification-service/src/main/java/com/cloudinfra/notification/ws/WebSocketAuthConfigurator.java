package com.cloudinfra.notification.ws;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * WebSocket 握手鉴权 Configurator (JSR-356 标准做法).
 *
 * <p>本 Configurator 配合 {@link WebSocketAuthFilter} 工作:
 * <ol>
 *   <li>Filter 在 Upgrade 握手阶段校验 X-User-Id, 通过则写入 HttpSession</li>
 *   <li>Configurator 在 modifyHandshake 阶段从 HttpSession 读取, 注入 session.getUserProperties()</li>
 *   <li>业务 Handler @OnOpen 从 userProperties 读取 userId, 不再信任 URL 参数</li>
 * </ol>
 *
 * <p><b>为何不直接用 Configurator.modifyHandshake 读 Header</b>:
 * 该方法签名 3 参需要 jakarta.websocket-api 2.3+ 暴露 {@link HandshakeResponse},
 * 当前镜像无该版本, 故采用 Filter + Configurator 协同方案 (方案 C 变体).</p>
 *
 * <p><b>回退方案</b>: 若 jakarta.websocket-api 2.3+ 可用, 可直接在本类的 modifyHandshake 中
 * 通过 {@code request.getHeaders()} 读 X-User-Id, 移除 Filter.</p>
 */
@Slf4j
public class WebSocketAuthConfigurator extends ServerEndpointConfig.Configurator {

    public static final String USER_ID_PROPERTY = "auth.userId";
    public static final String USER_NAME_PROPERTY = "auth.userName";
    public static final String TENANT_ID_PROPERTY = "auth.tenantId";

    @Override
    public void modifyHandshake(ServerEndpointConfig sec,
                                HandshakeRequest request,
                                HandshakeResponse response) {
        // tyrus 2.2 + jakarta.websocket-api 2.2 下 HandshakeResponse 不可见, 但方法仍可重写
        // (基类 Configurator 内部已实现). 此处仅做 userProperties 注入, 实际鉴权在 WebSocketAuthFilter.
        HttpSessionWrapper session = new HttpSessionWrapper(request.getHttpSession());
        String userId = session.getAttribute(USER_ID_PROPERTY);
        String userName = session.getAttribute(USER_NAME_PROPERTY);
        String tenantId = session.getAttribute(TENANT_ID_PROPERTY);

        if (userId == null || userId.isBlank()) {
            String sessionId = "unknown";
            if (request.getHttpSession() != null) {
                try {
                    Object id = request.getHttpSession().getClass()
                        .getMethod("getId").invoke(request.getHttpSession());
                    if (id != null) {
                        sessionId = id.toString();
                    }
                } catch (Exception ignored) {
                    // ignore
                }
            }
            log.warn("WebSocket 握手阶段 userId 缺失, Filter 阶段校验未通过? sessionId={}", sessionId);
            return;
        }

        sec.getUserProperties().put(USER_ID_PROPERTY, userId);
        if (userName != null) {
            sec.getUserProperties().put(USER_NAME_PROPERTY, userName);
        }
        if (tenantId != null) {
            sec.getUserProperties().put(TENANT_ID_PROPERTY, tenantId);
        }
        log.debug("WebSocket Configurator 注入 userId={}, tenantId={}", userId, tenantId);
    }

    /**
     * 简化 HttpSession 包装, 兼容 jakarta.servlet (避免 import 错误).
     */
    private static class HttpSessionWrapper {
        private final Object session;

        HttpSessionWrapper(Object session) {
            this.session = session;
        }

        @SuppressWarnings("unchecked")
        <T> T getAttribute(String name) {
            if (session == null) {
                return null;
            }
            try {
                // 反射调用 jakarta.servlet.http.HttpSession.getAttribute(String)
                return (T) session.getClass()
                    .getMethod("getAttribute", String.class)
                    .invoke(session, name);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
