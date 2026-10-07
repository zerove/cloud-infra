# 架构指南

本文档描述了 Cloud-Infra 项目中采用的架构决策和设计模式。

## 1. 核心设计哲学

### 基于虚拟线程的高并发
项目利用 **JDK 25 虚拟线程 (Virtual Threads)** 来处理大规模 I/O 并发，而无需承担平台线程的沉重开销。
- **配置**: 通过 `spring.threads.virtual.enabled=true` 启用。
- **执行**: `VirtualThreadConfig` 提供了基于 `Executors.newVirtualThreadPerTaskExecutor()` 的 `TaskExecutorAdapter`，确保 `@Async` 任务和 Spring MVC 请求均在虚拟线程中运行。

### 模块化框架架构
本项目摒弃了臃肿的 "common" 模块，采用了 **分层框架方法** (`cloud-infra-framework`)：
- `framework-web`: 标准化 WebMvc, OpenAPI 和 Actuator 配置。
- `framework-security`: 提供所有服务共用的 `JwtAuthenticationFilter` 和 `PermissionService`。
- `framework-mybatis`: 配置 MyBatis Plus 和分页插件。
- `framework-redis`: 使用 Redisson 封装分布式锁和缓存。
- `framework-tracing`: 集成 Micrometer Tracing。

## 2. 请求流与生命周期

### 网关模式
`cloud-infra-gateway` 作为 **安全周界**:
1. **追踪注入**: 如果请求缺失 `traceId`，则由网关生成。
2. **认证校验**: `GlobalAuthFilter` 解析 JWT。若无效或缺失，直接返回 `401 Unauthorized`。
3. **身份传播**: 网关封装请求并添加 `X-User-Id` 和 `X-User-Name` 请求头。这消除了下游服务重复校验 JWT 的必要。

### 分布式 RBAC 流程
1. **用户登录**: `auth-service` 验证凭据并返回包含 `userId` 和 `subject` 的 JWT。
2. **授权检查**: 下游服务（如 `system-module`）调用 `PermissionService`。
3. **权限解析**: `PermissionService` 根据网关传递的请求头（由 `JwtAuthenticationFilter` 填充至 `SecurityContext`）校验请求的权限字符串。
4. **超级用户绕过**: 拥有 `admin` 角色或 `*:*:*` 权限的用户将绕过所有具体检查。

## 3. 数据策略

### 持久化
- **主数据库**: 使用 PostgreSQL 16 存储结构化关系数据。
- **ORM**: 采用 MyBatis Plus，兼顾复杂查询的灵活性与基础 CRUD 的便捷性。
- **迁移**: 集成 Flyway 进行版本化的 Schema 迁移。

### 缓存
- **分布式缓存**: 基于 Redis 7.2 和 Redisson。
- **模式**: 用于会话令牌、系统配置缓存以及限流 (`RedisRateLimiter`)。

## 4. 可观测性栈

- **链路追踪**: Micrometer Tracing $\rightarrow$ Zipkin/Jaeger (可选)。
- **指标监控**: Micrometer $ightarrow$ Prometheus $ightarrow$ Grafana。
- **健康检查**: Spring Boot Actuator 提供 `/health` 和 `/info` 端点，用于 Kubernetes/Podman 编排。

## 5. WebSocket 实时推送 (M7-03)

通知服务（`notification-service`）通过 JSR-356 `@ServerEndpoint` 暴露 WebSocket 端点，向在线用户实时推送通知。

### 5.1 端点与协议

- **端点**: `ws://host:8084/notification/ws` （生产经 Nginx 终止 TLS 为 `wss://`）
- **消息协议**:
  - 客户端 → 服务端: 文本 `ping` 心跳
  - 服务端 → 客户端: 文本 `pong` 响应（心跳应答）
  - 服务端 → 客户端: JSON `{ type: "notification", userId, payload, timestamp }` 业务推送
  - 服务端 → 客户端: JSON `{ type: "connected", userId, message }` 握手成功通知

### 5.2 鉴权 (M7-03 改造)

**设计原则**: 绝不信任 URL 参数中的 userId（客户端可伪造），必须从握手 Header 提取（由 Gateway 验签后注入）。

**鉴权链路** (Filter + Configurator 协同方案)：

```
客户端 → Gateway (验签 JWT, 注入 X-User-Id) → Nginx (可选 TLS 终止) 
  → WebSocketAuthFilter (校验 X-User-Id 写 HttpSession) 
  → WebSocketAuthConfigurator (从 HttpSession 反射读取, 注入 userProperties) 
  → @OnOpen (从 userProperties 读取 userId, 缺失则关闭)
```

**关键文件**:
- `WebSocketAuthFilter` (`notification/ws/`): Servlet Filter，仅作用于 `Upgrade: websocket` 请求，缺失 `X-User-Id` 返回 401
- `WebSocketAuthConfigurator` (`notification/ws/`): JSR-356 `Configurator.modifyHandshake`，从 HttpSession 反射读取 userId 注入 `session.getUserProperties()`
- `NotificationWebSocketHandler` (`notification/ws/`): `@ServerEndpoint(value="/notification/ws", configurator=...)`，`@OnOpen` 从 userProperties 读取

**为何选 Servlet Filter + Configurator 协同**：
- 方案 A 需 `jakarta.websocket-api 2.3+` 暴露 `HandshakeResponse` 3 参签名，当前镜像无该版本
- 方案 B (Spring `HandshakeInterceptor`) 仅对 Spring WebSocketHandler 生效，不可用于 JSR-356 `@ServerEndpoint`
- 方案 C 变体：Filter 在握手前拦截校验，Configurator 从 HttpSession 读取注入 userProperties，完全规避 API 版本依赖

### 5.3 心跳与超时

- **心跳协议**: 文本 `ping`/`pong`（业务自定义，onMessage 处理），同时容器配置 `setMaxSessionIdleTimeout(30min)` 防止僵尸连接
- **容器配置**: `ServletServerContainerFactoryBean` 设置 `MaxTextMessageBufferSize=64KB`、`MaxSessionIdleTimeout=30min`

### 5.4 TLS (wss://)

**推荐架构**: Nginx 终止 TLS → 后端 `ws://` 明文（PaaS 内部网络可信）

**Nginx 配置** (见 `deploy/nginx/notification-wss.conf`):
- TLS 1.3 + TLS 1.2 协议
- 强加密套件 (AES-256-GCM, CHACHA20-POLY1305)
- HSTS (`Strict-Transport-Security: max-age=63072000`)
- WebSocket 必需头: `Upgrade $http_upgrade` + `Connection "upgrade"`
- 透传 `X-User-Id` / `X-User-Name` / `X-Tenant-Id` Header 到后端
- 代理超时 1800s 与容器 idle timeout 对齐

**证书生成** (开发/演示): `deploy/ssl/generate-notification-cert.sh` 生成自签 PKCS12 keystore（RSA 2048, 365d, SAN 包含 dns/ip）

**生产**: 应使用 Let's Encrypt 或企业 CA 签发证书，避免使用自签证书（浏览器会拦截）

### 5.5 可观测性

通知服务通过 Micrometer 暴露 WebSocket 指标：

| 指标 | 类型 | 说明 |
|---|---|---|
| `notification.ws.active_connections` | Gauge | 当前活跃 WebSocket 连接数 |
| `notification.ws.active_users` | Gauge | 当前活跃用户数 |
| `notification.ws.push.total{result=success\|failure}` | Counter | 推送成功/失败次数 |
| `notification.ws.ping.total` | Counter | 客户端 ping 心跳次数 |

这些指标接入 Prometheus，可在 `business` Dashboard 中查看（参考 M6-06 业务告警 Dashboard）。
