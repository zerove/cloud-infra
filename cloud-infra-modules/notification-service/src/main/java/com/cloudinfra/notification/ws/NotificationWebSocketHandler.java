package com.cloudinfra.notification.ws;

import com.cloudinfra.notification.config.MetricsBinder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
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
 * <p>线程安全: 使用 ConcurrentHashMap + Set 自身同步.
 * 推送失败自动从映射中移除 session, 避免内存泄漏.</p>
 *
 * <p>端点: ws://host:8084/notification/ws/{userId}</p>
 * <p>协议: 客户端发 "ping" 文本 → server 回 "pong".
 * 业务推送: server -> client 发送 JSON { type: "notification", userId, payload, timestamp }.</p>
 */
@Slf4j
@Component
@ServerEndpoint("/notification/ws/{userId}")
public class NotificationWebSocketHandler {

    private static final Map<Long, Set<Session>> sessions = new ConcurrentHashMap<>();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static volatile Counter PUSH_SUCCESS;
    private static volatile Counter PUSH_FAILURE;
    private static volatile Counter PING_RECEIVED;

    /**
     * Spring 启动后注入 MeterRegistry, 注册 WS 指标.
     * 使用 static @PostConstruct 避免 JSR-356 @ServerEndpoint 自行 new 实例绕过 Spring 注入.
     * 业务方可访问:
     * - notification.ws.active_connections (gauge)
     * - notification.ws.active_users (gauge)
     * - notification.ws.push.total{result=success|failure} (counter)
     * - notification.ws.ping.total (counter)
     */
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
    public void onOpen(Session session, @PathParam("userId") Long userId) {
        if (userId == null) {
            log.warn("WebSocket 缺少 userId, 关闭连接: sessionId={}", session.getId());
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "missing userId"));
            } catch (IOException ignored) {
                // ignore
            }
            return;
        }
        sessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("WebSocket 连接建立: userId={}, sessionId={}, 当前连接数={}",
            userId, session.getId(), sessions.get(userId).size());

        // 发送欢迎消息
        sendJson(session, Map.of(
            "type", "connected",
            "userId", userId,
            "message", "Notification stream established"
        ));
    }

    @OnMessage
    public void onMessage(String payload, Session session) {
        // 业务侧只需 server -> client, 客户端可发 ping 心跳
        if ("ping".equalsIgnoreCase(payload.trim())) {
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

    private static Long extractUserId(Session session) {
        try {
            String userIdStr = session.getPathParameters().get("userId");
            if (userIdStr != null) {
                return Long.parseLong(userIdStr);
            }
        } catch (NumberFormatException ignored) {
            // ignore
        }
        return null;
    }
}
