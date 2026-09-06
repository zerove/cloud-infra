package com.cloudinfra.notification.ws;

import com.cloudinfra.notification.config.MetricsBinder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知 WebSocket Handler (JSR-356 注解模式, 兼容 Spring Boot 4.1)
 *
 * <p>维护 userId -> Set<Session> 映射, 一个用户多个设备连接时全部推送.
 * 业务侧调用 {@link #pushToUser(Long, Object)} 即可向某用户所有活跃连接广播.</p>
 *
 * <p><b>M7-03 鉴权改造</b>: 移除 URL 中的 userId ({@code /notification/ws/{userId}}),
 * 改为 {@code /notification/ws} 无参路径, userId 来自 {@link WebSocketAuthConfigurator}
 * 在握手阶段注入的 session.getUserProperties() (从 X-User-Id header 提取).
 * 客户端无法伪造 URL 中的 userId, 安全性大幅提升.</p>
 *
 * <p><b>心跳</b>: 文本 ping/pong (业务自定义, onMessage 协议), 同时支持 RFC 6455 帧级 onPong.</p>
 *
 * <p>线程安全: 使用 ConcurrentHashMap + Set 自身同步.
 * 推送失败自动从映射中移除 session, 避免内存泄漏.</p>
 */
@Slf4j
@Component
@ServerEndpoint(value = "/notification/ws", configurator = WebSocketAuthConfigurator.class)
public class NotificationWebSocketHandler {

    private static final Map<Long, Set<Session>> sessions = new ConcurrentHashMap<>();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static volatile Counter PUSH_SUCCESS;
    private static volatile Counter PUSH_FAILURE;
    private static volatile Counter PING_RECEIVED;

    @jakarta.annotation.PostConstruct
    public void initMetrics() {
        MeterRegistry registry = MetricsBinder.getRegistry();
        if (registry == null) {
            log.warn("MeterRegistry 尚未注入, WebSocket metrics 暂不注册");
            return;
        }
        java.util.function.ToDoubleFunction<Map<Long, Set<Session>>> connFn =
            m -> m.values().stream().mapToInt(Set::size).sum();
        java.util.function.ToDoubleFunction<Map<Long, Set<Session>>> userFn =
            m -> (double) m.size();
        registry.gauge("notification.ws.active_connections", sessions, connFn);
        registry.gauge("notification.ws.active_users", sessions, userFn);
        PUSH_SUCCESS = Counter.builder("notification.ws.push.total")
            .description("WebSocket 推送成功次数")
            .tag("result", "success").register(registry);
        PUSH_FAILURE = Counter.builder("notification.ws.push.total")
            .description("WebSocket 推送失败次数")
            .tag("result", "failure").register(registry);
        PING_RECEIVED = Counter.builder("notification.ws.ping.total")
            .description("客户端 ping 心跳次数")
            .register(registry);
    }

    @OnOpen
    public void onOpen(Session session) {
        // M7-03 鉴权: userId 必须由 Configurator 注入, 缺失则拒绝
        String userIdStr = (String) session.getUserProperties()
            .get(WebSocketAuthConfigurator.USER_ID_PROPERTY);
        if (userIdStr == null || userIdStr.isBlank()) {
            log.warn("WebSocket 拒绝: 握手阶段未通过鉴权 (缺少 X-User-Id), sessionId={}",
                session.getId());
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY,
                    "missing X-User-Id header"));
            } catch (IOException ignored) {
                // ignore
            }
            return;
        }
        final Long userId;
        try {
            userId = Long.parseLong(userIdStr);
        } catch (NumberFormatException e) {
            log.warn("WebSocket 拒绝: X-User-Id 非数字, userId={}", userIdStr);
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY,
                    "invalid X-User-Id"));
            } catch (IOException ignored) {
                // ignore
            }
            return;
        }

        sessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("WebSocket 连接建立 (鉴权通过): userId={}, sessionId={}, 当前连接数={}",
            userId, session.getId(), sessions.get(userId).size());

        sendJson(session, Map.of(
            "type", "connected",
            "userId", userId,
            "message", "Notification stream established"
        ));
    }

    @OnMessage
    public void onMessage(String payload, Session session) {
        // 业务侧只需 server -> client, 客户端可发 ping 心跳
        if (payload != null && "ping".equalsIgnoreCase(payload.trim())) {
            if (PING_RECEIVED != null) {
                PING_RECEIVED.increment();
            }
            sendJson(session, Map.of("type", "pong"));
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        Long userId = extractUserId(session);
        if (userId != null) {
            Set<Session> userSessions = sessions.get(userId);
            if (userSessions != null) {
                userSessions.remove(session);
                if (userSessions.isEmpty()) {
                    sessions.remove(userId);
                }
            }
        }
        log.info("WebSocket 连接关闭: userId={}, sessionId={}, reason={}",
            userId, session.getId(), reason);
    }

    @OnError
    public void onError(Session session, Throwable t) {
        log.warn("WebSocket 错误: sessionId={}, err={}", session.getId(), t.getMessage());
    }

    /**
     * 向指定用户的所有活跃连接推送通知 (静态方法, 可被任意 service 调用)
     */
    public static int pushToUser(Long userId, Object payload) {
        Set<Session> userSessions = sessions.get(userId);
        if (userSessions == null || userSessions.isEmpty()) {
            log.debug("用户 {} 无活跃 WebSocket 连接, 跳过推送", userId);
            return 0;
        }
        String json;
        try {
            json = OBJECT_MAPPER.writeValueAsString(Map.of(
                "type", "notification",
                "userId", userId,
                "payload", payload,
                "timestamp", System.currentTimeMillis()
            ));
        } catch (JsonProcessingException e) {
            log.error("序列化推送消息失败: userId={}", userId, e);
            return 0;
        }
        int success = 0;
        for (Session session : userSessions) {
            if (sendText(session, json)) {
                success++;
            }
        }
        log.info("WebSocket 推送: userId={}, 成功={}/{}", userId, success, userSessions.size());
        return success;
    }

    public static int activeConnectionCount() {
        return sessions.values().stream().mapToInt(Set::size).sum();
    }

    public static int activeUserCount() {
        return sessions.size();
    }

    // ---- 私有方法 ----

    private static void sendJson(Session session, Object payload) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(payload);
            sendText(session, json);
        } catch (JsonProcessingException e) {
            log.warn("发送 WebSocket 消息失败: sessionId={}, err={}", session.getId(), e.getMessage());
        }
    }

    private static boolean sendText(Session session, String text) {
        if (!session.isOpen()) {
            return false;
        }
        try {
            session.getBasicRemote().sendText(text);
            if (PUSH_SUCCESS != null) {
                PUSH_SUCCESS.increment();
            }
            return true;
        } catch (IOException e) {
            if (PUSH_FAILURE != null) {
                PUSH_FAILURE.increment();
            }
            log.warn("WebSocket 发送失败, 关闭 session: sessionId={}, err={}",
                session.getId(), e.getMessage());
            try {
                session.close();
            } catch (IOException ignored) {
                // ignore
            }
            return false;
        }
    }

    /**
     * M7-03 改造: userId 来源从 URL pathParam 改为 session userProperties (Configurator 注入)
     */
    private static Long extractUserId(Session session) {
        if (session == null) {
            return null;
        }
        String userIdStr = (String) session.getUserProperties()
            .get(WebSocketAuthConfigurator.USER_ID_PROPERTY);
        if (userIdStr == null) {
            return null;
        }
        try {
            return Long.parseLong(userIdStr);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
