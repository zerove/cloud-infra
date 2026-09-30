# M7-03: WebSocket 生产化 (TLS + 心跳 + 鉴权)

Status: in progress (2026-09-18，TLS 隔离组件集成验证完成，生产入口验收待补)
Priority: P1
Owner: AI Agent
Module: cloud-infra-modules/notification-service
Depends: 无

## 背景 (PRD M7-03)

> WebSocket 生产化 (TLS + 心跳 + 鉴权).

## Phase B 鉴权改造精确阻塞分析 (2026-09-05 12:11 实测)

JSR-356 `Configurator.modifyHandshake(sec, request, response)` 签名使用 `jakarta.websocket.HandshakeResponse`, 但 `jakarta.websocket-api 2.2.0` jar 中**只暴露 `HandshakeRequest`**, `HandshakeResponse` 缺失. 实测:

```
jakarta.websocket-api 2.2.0 内:
  - jakarta/websocket/server/HandshakeRequest.class  (✅ 存在)
  - jakarta/websocket/HandshakeResponse.class  (❌ 缺失, 需 jakarta.websocket-api 2.3+)
  - ServerEndpointConfig$Configurator.modifyHandshake 3 参 (编译失败)
```

tyrus-core 2.2.0 内部 `TyrusServerEndpointConfigurator` 实现了完整 3 参 modifyHandshake, 但 tyrus-core 是 **runtime scope**, 业务代码不能直接引用.

**3 种解决路径 (后续 session 选型)**:

### 方案 A: 升级 jakarta.websocket-api 到 2.3+ (推荐)

- 检查 Maven 仓库最新 jakarta.websocket-api 版本
- 修改 `cloud-infra-modules/notification-service/pom.xml` 加 `jakarta.websocket:jakarta.websocket-api:2.3.0+` compile scope
- 业务代码可直接 import `jakarta.websocket.HandshakeResponse`
- 风险: 需 spring-boot-starter-websocket 兼容该版本

### 方案 B: 用 spring 的 HandshakeInterceptor (不同包)

- `org.springframework.web.socket.server.HandshakeInterceptor` (Spring 包装, 非 JSR-356)
- 与 spring-websocket 集成, 但**仅对 spring WebSocketHandler 生效, 不可用于 `@ServerEndpoint` 注解模式**
- 现有架构走 JSR-356 `@ServerEndpoint`, 改造量大

### 方案 C: 自定义 Servlet Filter 拦截 Upgrade 请求

- 拦截 HTTP `Upgrade: websocket` 握手请求
- 校验 X-User-Id 头 + 写 session 属性
- 通过 `HttpServletRequest.getSession()` 间接传值
- 风险: Filter 链与 WebSocket 握手时序难对齐

## 3 方案选型结果 (2026-09-06 Phase A 实施选定)

### 选定: 方案 C 变体 (Filter + Configurator 协同)

实施路径:
1. **`WebSocketAuthFilter`** (Servlet Filter, OncePerRequestFilter): 拦截 `/notification/ws` Upgrade 请求, 校验 `X-User-Id` header, 通过则写入 HttpSession 属性, 缺失则 401
2. **`WebSocketAuthConfigurator`** (JSR-356): modifyHandshake 阶段从 HttpSession 反射读取 userId, 注入 session.getUserProperties()
3. **`NotificationWebSocketHandler`**: 移除 URL 中的 userId, 改为 `@ServerEndpoint("/notification/ws")`, @OnOpen 从 userProperties 读取, 缺失则关闭连接
4. **`NotificationSecurityConfig`**: `/notification/ws/**` 仍 permitAll (浏览器 WS 不带 Header, 由 Filter + Configurator 鉴权)

**为何选 C 变体而非 A**:
- 方案 A 需 `jakarta.websocket-api 2.3+` 暴露 `HandshakeResponse` 3 参签名
- 阿里云 maven 镜像无该版本, 编译失败
- 方案 C 不依赖 API 升级, 反射 HttpSession 解耦 (后续 jakarta.websocket-api 2.3+ 可用时, 可移除 Filter, 改为 Configurator 直接读 Header)

**测试**: 7/7 通过
- `WebSocketAuthFilterTest` (5): 非 Upgrade 放行 / 缺 X-User-Id 401 / 合法 X-User-Id 写 session / 空字符串 401 / 非 WS 路径不过滤
- `WebSocketAuthConfiguratorTest` (2): 常量正确 / 可实例化

**commit**: `6c20099 feat(m7-03): WebSocket 鉴权 Header 化 + Configurator + Filter 7 测试`

## 现状盘点 (2026-09-05)

| 模块 | 文件 | 状态 | 备注 |
|---|---|---|---|
| notification-service | `spring-boot-starter-websocket` | ✅ 依赖 | spring-boot-starter-websocket 3.5.x |
| notification-service | `WebSocketConfig` | ✅ 完整 | JSR-356 ServerEndpointExporter (自动扫描 @ServerEndpoint) |
| notification-service | `NotificationWebSocketHandler` | ✅ 完整 | `@ServerEndpoint("/notification/ws/{userId}")` |
| notification-service | `NotificationServiceImpl` | ✅ 集成 | 事务提交后 pushToUser 实时推送 |
| notification-service | `NotificationSecurityConfig` | ✅ 完整 | `/notification/ws/**` permitAll |
| notification-service | `MetricsBinder` | ✅ 完整 | WS 指标桥接 |
| notification-service | `tyrus-core` | ✅ 依赖 | 2.2.0 (runtime scope) |
| **TLS (wss://)** | 未配 | ❌ **缺失** | 需 HTTPS 配置 + 证书 |
| **心跳 (heartbeat)** | **已实现** (onMessage 'ping' → 'pong' JSON) | ✅ | 文本 ping-pong |
| **鉴权 (X-User-Id)** | userId 在 URL (可伪造) | ❌ **安全风险** | 应从 SecurityContext 提取, 不信任 URL |
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
- [x] **2026-09-05 12:11**: 精确化 Phase B 阻塞分析 (3 方案: A 升级 jakarta.websocket-api 2.3+ / B spring HandshakeInterceptor / C Servlet Filter), 推荐方案 A
- [x] **2026-09-06 09:38**: Phase A 完成 - 采用方案 C 变体 (Filter + Configurator 协同), 7/7 测试通过, commit 6c20099
- [x] Phase B: 直接 TLS 配置、无默认密码的证书脚本、可选编排覆盖文件。Nginx 方案尚未运行时验证。
- [x] Phase C 组件集成测试：2026-09-18 通知模块 20 项通过。真实 TLS 1.2/1.3 握手、缺头 401、心跳、推送、连接清理、证书拒绝覆盖和无效密码。
- [ ] 生产入口验收：可信网关身份校验、浏览器升级、完整安全过滤链、容器证书挂载和管理健康检查。不能用隔离组件测试替代。

## ⚠️ 2026-09-29 验收前置发现: WebSocket 身份信任模型缺口（已实证）

在准备"可信网关身份校验"验收项时，先用真实 TLS 端点实测了当前信任模型
（`WebSocketIdentityTrustIT`，2 项，全部通过 —— 记录的是**当前行为**）：

| 场景 | 实测结果 |
|---|---|
| 不带 `X-User-Id` | 被拒（`WebSocketHandshakeException`） ✅ 已实现 |
| 携带任意 `X-User-Id: 999999`、**无任何网关签名** | **连接成功** ❌ 缺口 |

**问题**：`WebSocketAuthFilter` 只校验 `X-User-Id` 是否**存在**，不校验其是否真由
网关注入。而 HTTP 侧的对应机制（`SystemSecurityConfig.internalCallMatcher` 与
`HeaderAuthenticationFilter.isTrustedInternalRequest`）会用 `InternalCallCrypto` 校验
HMAC 签名。两处信任模型不一致。

**潜在后果**：任何能直连 notification-service 端口的一方，仅需发送
`X-User-Id: <任意 ID>` 即可冒充该用户，订阅其实时通知（`pushToUser` 按 userId 推送）。

**是否构成实际风险取决于该端口的网络暴露面**（已逐项核实）：

| 部署方式 | 暴露面 | 可利用性 |
|---|---|---|
| `deploy/compose.yaml` | `ports: "8084:8084"` + 管理端口 `9305:9301` 映射宿主机 | **宿主机网络内可直接利用** |
| `deploy/k8s/notification/values.yaml` | `type: ClusterIP` | 仅集群内可达，取决于集群内网络策略 |

即：**compose 环境下该缺口可直接利用**；K8s 环境下取决于集群内是否存在其他可直连
该 Service 的工作负载。这是本 issue 生产入口验收未覆盖的部分。

**两个候选方案（需决策，本轮未擅自实施）**：

| 方案 | 内容 | 代价 |
|---|---|---|
| A. 网络层隔离 | 端口不对外暴露，仅网关可达；把"仅对网关开放"作为部署前提写入文档与 compose | 不改代码，但依赖运维纪律，需验证 compose 的端口暴露配置 |
| B. 与 HTTP 对齐 | `WebSocketAuthFilter` 复用 `InternalCallCrypto` 校验签名（可用同一 `cloud-infra.security.verify-internal-sign` 开关），由网关注入时签名 | 需确认网关 WS 转发路径会带上签名头；浏览器仍走网关不受影响 |

**建议**：先做 A 的暴露面核查（成本低，直接回答"是否可被利用"），再决定是否需要 B。
本 issue 剩余验收项（浏览器升级、容器证书挂载、管理健康检查）均需完整 compose 栈，
而 `deploy/compose.yaml` **当前没有 nginx 服务**（`deploy/nginx/notification-wss.conf`
无对应容器），即该链路尚未搭建。搭建属于"新建"而非"验收"，应单独评估。

## 不在本 issue 范围

- WS 集群扩展 (Redis pub/sub, 留 M7+)
- WS 性能压测 (留 benchmark)
- 浏览器兼容性测试 (留 QA)

## 关联

- PRD M7-03
- 现有 WebSocketConfig / NotificationWebSocketHandler
- HeaderAuthenticationFilter (跨服务认证)
- M6-01 多租户 (tenant_id 透传到 WS)
