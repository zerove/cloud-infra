# 权限认证系统设计 (Stateless JWT + Redis 黑名单)

**目标**：构建一个基于 JWT 的无状态认证体系，利用 Redis 实现 Token 主动失效控制（黑名单机制），并由网关统一管控。

## 1. 架构概览
- **认证方式**：基于 JWT 的无状态认证。
- **校验边界**：所有流量统一经由 `cloud-infra-gateway` 拦截。
- **状态管控**：使用 `framework-redis` 存储已注销的 Token ID (JTI)，确保注销操作即时生效。

## 2. 核心组件
### 2.1 网关鉴权 (Cloud Gateway)
- `AuthFilter`：实现 Spring Cloud Gateway 的 `GlobalFilter`。
- 校验流程：
  1. 拦截请求，解析 `Authorization: Bearer <token>` Header。
  2. 校验 JWT 签名有效性。
  3. 检查 Redis 黑名单：`exists("token:blacklist:" + jti)`。
  4. 鉴权通过后，将用户身份信息（User-ID, Roles）以 Header 形式透传至各后端微服务。

### 2.2 身份工具 (framework-security)
- 扩展 `JwtUtils`：支持生成携带唯一 `jti` (JWT ID) 的 Token，便于黑名单管控。

### 2.3 黑名单管理 (framework-redis)
- 提供 `RedisService` 扩展，支持 `setWithExpire` 操作。
- 关键策略：Token 注销时，从 JWT 中解析出 `exp`（过期时间），将 `jti` 存入 Redis，过期时间设为 `exp - now`。

## 3. 数据流向
1. **登录**：`cloud-infra-auth` 签发 JWT（含 `jti`）。
2. **请求**：网关校验 JWT 有效性 + Redis 黑名单验证。
3. **透传**：网关向后端透传 `X-User-Id` 等 Header。
4. **注销**：用户请求注销，将 `jti` 写入 Redis 黑名单，后续该 `jti` 的 Token 将被网关拦截。

## 4. 关键异常处理
- `401 Unauthorized`：Token 缺失或无效。
- `403 Forbidden`：Token 在黑名单中或权限不足。

---

**设计已完成，请 review 以上设计方案是否符合预期。如无异议，我将使用 `writing-plans` skill 创建具体的实施计划。**
