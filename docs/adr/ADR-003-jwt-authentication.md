# ADR-003: 基于 JWT 的无状态认证模型

**状态**: 已接受  
**日期**: 2026-04-21  
**提出者**: 架构组  

## 背景

微服务间需要一种高效的认证机制。传统 Session 共享方案存在跨服务状态同步复杂度高的问题，且不适合容器化部署的弹性伸缩场景。

## 决策

采用 **JWT 无状态令牌认证模型**，配合 Redis 作会话缓存和黑名单管理。

### 认证流程

1. **登录**: 客户端向 `cloud-infra-auth` 发送凭证 → `SysLoginService` 校验 → `AuthTokenServiceImpl` 签发 JWT（24h 有效期）+ RefreshToken（7d 有效期）
2. **请求鉴权**: 客户端携带 Token 请求 → `Gateway` 的 `SecurityFilter` → `AuthEvaluator` 执行三段式校验

### 三段式校验逻辑

1. **白名单匹配**: 是否在白名单路径中（精确匹配 / 前缀匹配）
2. **JWT 签名校验**: 使用 `JwtUtils`（auth0 java-jwt HMAC256）解析和验签
3. **会话验证**: 检查 `auth:session:{userId}:{jti}` 是否存在 Redis 中，以及 `blacklist:{jti}` 黑名单

### 身份传播

- 校验通过后，`IdentityRequestWrapper` 注入 `X-User-Id`、`X-User-Name`、`traceId` 到请求头
- 下游服务通过 `HeaderAuthenticationFilter` 解析请求头并设置 `SecurityContext`
- 使用 `@PreAuthorize` 或 `PermissionService` 执行 RBAC 鉴权

### Token 撤销

- 登出时将 `jti` 加入 Redis 黑名单（24h TTL）
- RefreshToken 存储在 Redis 中，服务端可主动失效

## 后果

**正面**:
- 无状态设计，易于水平扩展
- 减少跨服务 Session 同步开销
- 支持细粒度的 RBAC 权限控制

**负面**:
- JWT 签发后无法主动过期（依赖黑名单机制）
- Token 体积较大，HTTP 请求头传输有少量额外开销
- 需要管理 RefreshToken 的存储和轮转

## 关联

- 实施计划: `docs/superpowers/plans/2026-05-07-jwt-security-implementation.md`
- 相关代码: `JwtUtils`, `AuthEvaluator`, `SecurityFilter`, `AuthTokenServiceImpl`, `HeaderAuthenticationFilter`
