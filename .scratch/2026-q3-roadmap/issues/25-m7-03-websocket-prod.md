# M7-03: WebSocket 生产化 (TLS + 心跳 + 鉴权)

Status: open (survey done 2026-09-05, infra 90% 就位)
Priority: P1
Owner: AI Agent
Module: cloud-infra-modules/notification-service
Depends: 无 (前置 spring-boot-starter-websocket + WebSocketConfig 已就位)

## 背景 (PRD M7-03)

> WebSocket 生产化 (TLS + 心跳 + 鉴权).

## 现状盘点 (2026-09-05)

| 模块 | 文件 | 状态 | 备注 |
|---|---|---|---|
| notification-service | `spring-boot-starter-websocket` | ✅ 依赖 | spring-boot-starter-websocket 3.5.x |
| notification-service | `WebSocketConfig` | ✅ 完整 | JSR-356 ServerEndpointExporter (自动扫描 @ServerEndpoint) |
| notification-service | `NotificationWebSocketHandler` | ✅ 完整 | `@ServerEndpoint("/notification/ws/{userId}")` |
| notification-service | `NotificationServiceImpl` | ✅ 集成 | 事务提交后 pushToUser 实时推送 |
| notification-service | `NotificationSecurityConfig` | ✅ 完整 | `/notification/ws/**` permitAll |
| notification-service | `MetricsBinder` | ✅ 完整 | WS 指标桥接 |
| **TLS (wss://)** | 未配 | ❌ **缺失** | 需 HTTPS 配置 + 证书 |
| **心跳 (heartbeat)** | 未配 | ❌ **缺失** | 客户端 30s ping, server 10s pong |
| **鉴权 (X-User-Id)** | userId 在 URL | ❌ **缺失** | 应从 SecurityContext 提取, 不信任 URL |
| **容器配置** | 64KB/30min | ✅ 完整 | text 64KB, idle 30min |

## 验收标准 (PRD)

> M7-03: WebSocket 生产化 (TLS + 心跳 + 鉴权)

需补 3 项核心能力:
1. **TLS (wss://)**: HTTPS 终止 (生产环境)
2. **心跳**: 客户端 ping, server pong, 超时断开
3. **鉴权**: 信任 X-User-Id header (而非 URL)

## 实施阶段 (估时 3d)

### Phase A: 心跳 + 鉴权 (1.5d)

1. **新增 `WebSocketAuthInterceptor`**: 拦截握手请求, 提取 X-User-Id header 注入 SecurityContext
2. **`NotificationWebSocketHandler` 加 ping/pong 处理**: `onPing` / `onPong` 实现
3. **配置 `server.servlet.session.timeout`**: WebSocket idle 30min

### Phase B: TLS (1d)

1. **HTTPS 证书生成** (自签 or Let's Encrypt)
2. **Nginx 反向代理 wss://**: 终止 TLS, 转发 ws://
3. **`application-prod.yml`**: `server.ssl.*` 配置

### Phase C: 测试 + 文档 (0.5d)

1. **WebSocketAuthInterceptorTest**: 验证 X-User-Id 提取 + 拒绝无 X-User-Id
2. **心跳测试**: client ping 验证 server 在 10s 内 pong
3. **文档**: ARCHITECTURE.md 加 WebSocket TLS 章节

## 关键设计

### 鉴权改造

```java
// 当前: userId 从 URL 提取 (不可信)
@ServerEndpoint("/notification/ws/{userId}")
public class NotificationWebSocketHandler {
    public void onOpen(Session session, @PathParam("userId") String userId) {
        // 信任 URL → 安全隐患
    }
}

// 改造: userId 从 SecurityContext 提取 (HeaderAuthenticationFilter 已验证签名)
@ServerEndpoint("/notification/ws")
public class NotificationWebSocketHandler {
    public void onOpen(Session session) {
        String userId = SecurityContext.getUserId();  // 来自 X-User-Id header
    }
}
```

### 心跳 (RFC 6455)

```java
@Override
public void onPing(Session session, ByteBuffer message) {
    // RFC 6455 自动 pong, 只需在配置启用
    // application.yml: server.servlet.session.timeout=30m
    // WebSocket container 自动处理 ping → pong
}
```

## 进度

- [x] **2026-09-05 03:11**: kickoff + infra survey (本 issue)
- [ ] Phase A: 心跳 + 鉴权
- [ ] Phase B: TLS
- [ ] Phase C: 测试 + 文档

## 不在本 issue 范围

- WS 集群扩展 (Redis pub/sub, 留 M7+)
- WS 性能压测 (留 benchmark)
- 浏览器兼容性测试 (留 QA)

## 关联

- PRD M7-03
- 现有 WebSocketConfig / NotificationWebSocketHandler
- HeaderAuthenticationFilter (跨服务认证)
- M6-01 多租户 (tenant_id 透传到 WS)