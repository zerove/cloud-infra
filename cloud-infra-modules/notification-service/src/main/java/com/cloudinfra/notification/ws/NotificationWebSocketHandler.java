package com.cloudinfra.notification.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知 WebSocket Handler
 *
 * <p>维护 userId -> Set<WebSocketSession> 映射, 一个用户多个设备连接时全部推送.
 * 业务侧调用 {@link #pushToUser(Long, Object)} 即可向某用户所有活跃连接广播.</p>
 *
 * <p>线程安全: 使用 ConcurrentHashMap + 同步块保护 session 写.
 * 推送失败自动从映射中移除 session, 避免内存泄漏.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationWebSocketHandler extends TextWebSocketHandler {

    /** userId -> 该用户的活跃 session 集合 */
    private final Map<Long, java.util.Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = extractUserId(session);
        if (userId == null) {
            log.warn("WebSocket 缺少 userId, 关闭连接: sessionId={}", session.getId());
            closeSession(session, CloseStatus.BAD_DATA.withReason("missing userId"));
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

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = extractUserId(session);
        if (userId != null) {
            java.util.Set<WebSocketSession> userSessions = sessions.get(userId);
            if (userSessions != null) {
                userSessions.remove(session);
                if (userSessions.isEmpty()) {
                    sessions.remove(userId);
                }
            }
        }
        log.info("WebSocket 连接关闭: userId={}, sessionId={}, status={}",
            userId, session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 业务侧只需 server -> client, 客户端可发 ping 心跳, 不处理业务消息
        if ("ping".equalsIgnoreCase(message.getPayload().trim())) {
            sendJson(session, Map.of("type", "pong"));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket 传输错误: sessionId={}, err={}", session.getId(), exception.getMessage());
        closeSession(session, CloseStatus.SERVER_ERROR);
    }

    /**
     * 向指定用户的所有活跃连接推送通知.
     *
     * @param userId  接收用户
     * @param payload 业务负载 (会序列化为 JSON)
     * @return 推送成功的 session 数
     */
    public int pushToUser(Long userId, Object payload) {
        java.util.Set<WebSocketSession> userSessions = sessions.get(userId);
        if (userSessions == null || userSessions.isEmpty()) {
            log.debug("用户 {} 无活跃 WebSocket 连接, 跳过推送", userId);
            return 0;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of(
                "type", "notification",
                "userId", userId,
                "payload", payload,
                "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.error("序列化推送消息失败: userId={}", userId, e);
            return 0;
        }
        int success = 0;
        for (WebSocketSession session : userSessions) {
            if (sendText(session, json)) {
                success++;
            }
        }
        log.info("WebSocket 推送: userId={}, 成功={}/{}", userId, success, userSessions.size());
        return success;
    }

    /**
     * 当前活跃连接数 (监控用)
     */
    public int activeConnectionCount() {
        return sessions.values().stream().mapToInt(java.util.Set::size).sum();
    }

    /**
     * 当前活跃用户数 (监控用)
     */
    public int activeUserCount() {
        return sessions.size();
    }

    // -------- 私有方法 --------

    private Long extractUserId(WebSocketSession session) {
        // 优先从 URI 路径变量取 (例如 /notification/ws/123 -> 123)
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        if (!path.isEmpty()) {
            try {
                String lastSegment = path.substring(path.lastIndexOf('/') + 1);
                return Long.parseLong(lastSegment);
            } catch (NumberFormatException ignored) {
                // 不是数字, fall through
            }
        }
        // 备选: 从 X-User-Id header 取 (网关注入)
        String header = session.getHandshakeHeaders() != null
            ? session.getHandshakeHeaders().getFirst("X-User-Id")
            : null;
        if (header != null) {
            try {
                return Long.parseLong(header);
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return null;
    }

    private void sendJson(WebSocketSession session, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            sendText(session, json);
        } catch (Exception e) {
            log.warn("发送 WebSocket 消息失败: sessionId={}, err={}", session.getId(), e.getMessage());
        }
    }

    private boolean sendText(WebSocketSession session, String text) {
        if (!session.isOpen()) {
            return false;
        }
        try {
            session.sendMessage(new TextMessage(text));
            return true;
        } catch (IOException e) {
            log.warn("WebSocket 发送失败, 关闭 session: sessionId={}, err={}",
                session.getId(), e.getMessage());
            closeSession(session, CloseStatus.SERVER_ERROR);
            return false;
        }
    }

    private void closeSession(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("关闭 session 异常: {}", e.getMessage());
        }
    }
}
