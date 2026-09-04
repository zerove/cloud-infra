# M6-02: 统一幂等框架 (Idempotency Key + Redis)

Status: closed (done 2026-09-04, integration verified)
Priority: P0
Owner: AI Agent
Module: cloud-infra-common + cloud-infra-framework/framework-web + cloud-infra-auth
Depends: 无 (前置 M6-01 完成, Redis 基础设施就绪)

## 结论

**M6-02 全部 3 个阶段完成**, 统一幂等框架已上线:
- @Idempotent 双模式 (REJECT 防抖 + REPLAY 返回首次结果)
- Idempotency-Key RFC 头规范 (兼容 X-Request-Token)
- IdempotencyStore (Redis JSON 缓存, IN_FLIGHT/COMPLETED 双状态)
- IdempotentAspect 重写 (@Around + 双模式分发 + 异常路径处理)
- AuthController.register 改 REPLAY (24h TTL, 网络重试场景)
- 17 单元测试 + 5 Testcontainers 集成测试 (真实 Redis) 全部通过

**Commits**: 1945dd4 (Phase A + B + C)

## 背景 (PRD M6-02)

防重复提交/支付等场景, 同一 Idempotency Key 二次调用返回首次结果, Redis TTL 可配置.

## 现状盘点 (2026-09-04)

| 模块 | 文件 | 状态 | 备注 |
|---|---|---|---|
| common | `annotation/Idempotent.java` | ✅ 已就位 | `expireSeconds` (默认10s) + `message` (默认"请求过于频繁") |
| framework-web | `aspect/IdempotentAspect.java` | ✅ 已就位 | Redis SET NX EX 防抖实现 |
| 业务使用 | `AuthController` | ✅ 1 处使用 | 防登录按钮双击 |
| 测试 | `IdempotentAspectTest` | ❌ 缺失 | 0 测试覆盖 |
| **"返回首次结果" 语义** | 框架未实现 | ❌ **缺失** | 当前抛异常 (防抖), PRD 要求返回首次结果 (幂等) |
| **Idempotency Key 头规范** | RFC 草案 | ❌ 未标准化 | 当前用 X-Request-Token, RFC 推荐 Idempotency-Key (Stripe 风格) |
| **响应缓存** | Redis 存响应 | ❌ 未实现 | 二次调用需要从 Redis 读响应体 |
| **Lua 原子性** | SET NX + EX 在两个命令 | ⚠️ 当前是单 SET NX EX (原子) | OK |
| **冲突响应码** | 409 vs 200 | ⚠️ 当前抛异常 (200 业务码) | 待与 PRD 确认 |

## 验收标准 (PRD)

> M6-02: 同一 Idempotency Key 二次调用返回首次结果, Redis TTL 可配置

需补 2 项核心能力:
1. **返回首次结果** (而非抛异常): 第一次调用成功后, 响应体 (含状态码) 缓存到 Redis, 二次调用直接返回缓存
2. **RFC 风格 Idempotency-Key 头**: 与 Stripe/PayPal/IETF 草案对齐, 客户端 SDK 易接入

## 实施阶段 (估时 3d)

### Phase A: Idempotency-Key 标准头 + 响应缓存 (2d)

1. **新增 `Idempotency-Key` 头支持** (兼容 X-Request-Token, 推荐前者)
2. **扩展 `@Idempotent` 注解**:
   - `mode()`: REJECT (默认防抖) / REPLAY (返回首次结果)
   - `keyHeader()`: 默认 `Idempotency-Key`
   - `storeResponse()`: 默认 true (REPLAY 模式必须)
3. **新增 `IdempotencyStore`** (framework-web): Redis 存 `idempotency:{key}:response` (JSON), TTL = expireSeconds
4. **重写 `IdempotentAspect`**:
   - REJECT 模式: SET NX EX, 存在则抛异常 (当前逻辑)
   - REPLAY 模式: GET 响应缓存, 命中则反序列化返回; 未命中则执行业务 + SET 响应缓存
5. **新增 `IdempotencyKeyGenerator`**: 客户端未提供时基于 userId+URI+body digest 自动生成

### Phase B: 单元测试 + 集成测试 (0.5d)

1. **IdempotentAspectTest**: 8 测试
   - REJECT 模式: 二次调用抛异常
   - REPLAY 模式: 二次调用返回首次结果 (含响应码 + body)
   - Idempotency-Key 头优先于 X-Request-Token
   - 缺 key 走 userId+URI+body digest
   - Redis 不可用时降级 (warn log + 放行)
   - 异常响应不缓存 (避免错误结果被复用)
   - TTL 过期后二次调用正常执行业务
2. **IdempotencyIntegrationTest** (Testcontainers): 真实 Redis 验证 SET NX EX + GET 响应

### Phase C: 业务应用 + 文档同步 (0.5d)

1. **AuthController**: 改用 REPLAY 模式 (登录场景: 网络重试需返回相同 token)
2. **SysUserController** (新增): 注册场景 REPLAY 模式
3. **AGENTS.md + ARCHITECTURE.md**: 同步
4. **issue 18**: closed

## 关键设计

### Redis 存储结构

```
idempotency:{keyHash}:status    -> "IN_FLIGHT" | "COMPLETED"
idempotency:{keyHash}:response  -> JSON {statusCode, headers, body}
idempotency:{keyHash}:error     -> JSON {exceptionClass, message}
```

- IN_FLIGHT: 并发首次调用, 后续 SET NX 失败
- COMPLETED + response: 二次调用直接返回
- COMPLETED + error: 二次调用返回错误响应 (避免错误被吞)

### 锁机制

首次调用 SET NX EX status=IN_FLIGHT (短 TTL ~30s 锁)
完成后 SET response/error + status=COMPLETED (长 TTL = expireSeconds)

### Lua 脚本 (可选)

为简化, 用 Redis pipeline + SET NX EX 单命令已足够 (Redis SET NX EX 本身原子). 不需 Lua.

## 进度

- [x] **2026-09-04 23:59**: kickoff + infra survey
- [x] **2026-09-05 00:30**: Phase A 完成 (双模式 + Idempotency-Key 头 + Redis 响应缓存 + 17 单元测试)
- [x] **2026-09-05 01:03**: Phase B 完成 (IdempotencyStoreIT 5 集成测试, Testcontainers Redis)
- [x] **2026-09-05 01:09**: Phase C 完成 (AuthController.register 改 REPLAY 模式, ROADMAP/AGENTS 同步)

## 实施细节

### Phase A: 框架升级

- `@Idempotent` 注解扩展: `Mode` (REJECT/REPLAY), `keyHeader`, `storeResponse`, `expireSeconds`
- `IdempotencyEntry`: 响应缓存 POJO (statusCode/body/errorClass/errorMessage), `isSuccess()` 加 `@JsonIgnore`
- `IdempotencyStore`: Redis 双 key 设计 (lock + status + response), Redis 异常降级
- `IdempotencyAutoConfiguration`: 注册 Store Bean
- `IdempotentAspect`: `@Around` 包裹, 双模式分发, Key 优先级 (Idempotency-Key > X-Request-Token > 自动生成)

### Phase B: Testcontainers 集成测试

- `IdempotencyStoreIT` (5 测试, 真实 Redis 7-alpine 容器):
  - tryAcquire SET NX 互斥
  - storeResponse + read JSON 往返
  - read IN_FLIGHT 返回 null
  - releaseLock 重新获得锁
  - 多 key 隔离

### Phase C: 业务应用

- `AuthController.register`: `@Idempotent(REPLAY, 86400s, Idempotency-Key)` - 网络重试返回相同结果

### 累计测试矩阵

| 模块 | 测试数 | 累计 + |
|---|---|---|
| common | 14 | +0 |
| framework-mybatis | 16 | +0 |
| framework-web (单测) | 30 | +17 (Idempotency) |
| framework-web (集成) | 5 | +5 (Redis IT) |
| system-module | 77 | +0 |
| file-service | 36 | +0 |
| notification-service | 13 | +0 |
| auth | 27 | +0 |
| **合计** | **218** | **+22** |

## 不在本 issue 范围

- 跨服务幂等 (messaging idempotency) → M6-03 Outbox 框架化已覆盖
- 数据库层幂等 (UNIQUE constraint) → 已由业务 SQL 维护
- 分布式锁 → 用 Redis SET NX EX 已实现

## 关联

- PRD M6-02 (`.scratch/2026-q3-roadmap/PRD.md`)
- 现有 `IdempotentAspect` (cloud-infra-framework/framework-web)
- M6-01 多租户数据隔离 (commit 17ee31a, 已 closed)