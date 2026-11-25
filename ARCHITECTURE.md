# 架构定义 (ARCHITECTURE.md)

> **当前状态**: v1.0.0 (2026-09-01) — Production-Ready Release
> **关键能力**: 11 个 ADR, 101 tests pass, P0-3b JWT blacklist 端到端 401 实测, M5-03 业务告警 Counter 运行时实测 (Counter 7.0)
> **git tag**: v1.0.0 on origin (e6b786d, 0 commit drift)

## 📐 分层结构 (Layering)
- **API Layer**: 
    - `cloud-infra-api`: 定义全平台共享的 Feign 客户端接口及 DTO，作为服务间契约。
    - `Controller`: 各模块入口，负责请求接收、参数校验及响应封装。
- **Service Layer**: 
    - `Service` 接口与 `ServiceImpl` 实现：承载核心业务逻辑、事务控制及跨模块协同。
- **Data Access Layer**: 
    - `Mapper/Repository`: 基于 MyBatis-Plus (PostgreSQL) 或 Elasticsearch 实现数据持久化与查询。
    - `Infrastructure`: 封装 Redis 缓存策略、RabbitMQ 消息生产/消费。

## 🔗 模块依赖图 (Dependency Graph)
- `cloud-infra-modules` $\rightarrow$ `cloud-infra-starter` (原因: 框架层能力经 starter **传递**引入)
- `cloud-infra-modules` $\rightarrow$ `cloud-infra-common` (原因: 依赖通用工具类及全局异常定义)
- `cloud-infra-modules` $\rightarrow$ `cloud-infra-api` (原因: 调用其他服务的 Feign 接口)
- 网关**不依赖** auth 模块: `cloud-infra-gateway/pom.xml` 只有 `cloud-infra-api` 与 `framework-security`。
  Token 校验由网关本地完成 (HMAC + Redis 会话), 无服务间调用

> **2026-10-13 更正与白盒化设计说明**: 本文档原写 `cloud-infra-modules → cloud-infra-framework`（直接依赖）。
> 实测(pom 逐个反查)是**无任何模块直接依赖任何 `framework-*` 模块**, 全部经
> `cloud-infra-starter` 传递:
> `starter → common + framework-{security,web,mybatis,redis,tracing}`。
> 
> **Starter 聚合的 6 大核心框架能力矩阵**：
> 1. `cloud-infra-common`: 核心工具与全局异常
> 2. `framework-security`: 鉴权与安全上下文 (`@PreAuthorize`, `HeaderAuthenticationFilter`)
> 3. `framework-web`: Web 底座、全局响应 `Result<T>`、虚拟线程适配
> 4. `framework-mybatis`: MyBatis-Plus、多租户拦截器 (`TenantSqlInterceptor`)、数据权限
> 5. `framework-redis`: Redis 缓存策略与分布式锁
> 6. `framework-tracing`: OpenTelemetry 链路追踪 (`X-Trace-Id`)
> 
> **架构影响**: 虽然简化了业务服务的 pom 编写，但隐式传递使开发者不易直接察觉其引入的切面与拦截器链。编写服务或排查问题时，应以此 starter 聚合矩阵为白盒依据。

## ⛔ 已移除的子系统 (2026-10-13)

以下四个子系统曾在此详述, 现已**从代码库删除**。它们共同的失败模式是:
**有完整实现与自测, 但零业务引用** —— 即"能力存在 ≠ 能力在运行"。

| 子系统 | 原规模 | 移除依据 |
| :-- | :-- | :-- |
| `cloud-infra-codegen` | 2,464 行 main | 零生产引用; 仅 `sys_post` 一表验证过 |
| `@Idempotent` 幂等 | 注解+切面+存储 ~376 行 | 唯一业务使用方已于 2026-09-29 移除 |
| Outbox 框架 | ~767 行 | 零使用方; notification 已在自身表上实现等价语义 |
| 业务编排引擎 | ~540 行 | 零业务使用方, 仅自身自动配置引用 |

删除而非冻结的理由: **无消费者且无维护者的代码, 其正确状态是删除** ——
"保留但暂停投入"只是更贵的删除, 且会持续误导后来者以为它可用。

框架准入原则见 `AGENTS.md`「框架能力准入原则」; PR 模板已加"第二个使用方"必答项。

## 🏢 多租户数据隔离 (M6-01, 2026-09)

**链路**: 客户端 → Gateway `SecurityFilter.propagateTenantHeader` → 业务 `TenantContextFilter` → `TenantContext` (ThreadLocal) → `TenantSqlInterceptor` → SQL `WHERE tenant_id = ?`

- **Schema**: V14 migration (9 张业务表 + tenant_id VARCHAR(50) + 11 索引)
- **注解**: `@TenantFilter` (类级或方法级, column + rejectWhenMissing)
- **拦截器链顺序**: Pagination → Tenant → DataScope (串联而非覆盖)
- **零业务侵入**: 业务 Mapper 加类级 `@TenantFilter` 即可, 无需改 SQL
- **可选启用**: `cloud-infra.tenant.enabled=false` 时 TenantContext.hasTenant() 短路, 兼容单租户模式
- **Gateway 防御**: `SecurityFilter` 透传 `X-Tenant-Id` 时校验 `[a-zA-Z0-9\-_]{1,50}`, 阻断 CRLF 注入与过长 DoS

> **2026-10 历史清理**: `cloud-infra-codegen`（可选代码生成工具，零业务引用）已从仓库移除；相关历史记录见 git history 与 ROADMAP/CONTEXT 归档条目。

## 🏢 多租户数据隔离 (M6-01, 2026-09)

**链路**: 客户端 → Gateway `SecurityFilter.propagateTenantHeader` → 业务 `TenantContextFilter` → `TenantContext` (ThreadLocal) → `TenantSqlInterceptor` → SQL `WHERE tenant_id = ?`

- **Schema**: V14 migration (9 张业务表 + tenant_id VARCHAR(50) + 11 索引)
- **注解**: `@TenantFilter` (类级或方法级, column + rejectWhenMissing)
- **拦截器链顺序**: Pagination → Tenant → DataScope (串联而非覆盖)
- **零业务侵入**: 业务 Mapper 加类级 `@TenantFilter` 即可, 无需改 SQL
- **可选启用**: `cloud-infra.tenant.enabled=false` 时 TenantContext.hasTenant() 短路, 兼容单租户模式
- **Gateway 防御**: `SecurityFilter` 透传 `X-Tenant-Id` 时校验 `[a-zA-Z0-9\-_]{1,50}`, 阻断 CRLF 注入与过长 DoS

## 🔁 统一幂等框架 (M6-02, 2026-09)

**链路**: 客户端 → Controller (`@Idempotent`) → `IdempotentAspect` → `IdempotencyStore` → Redis (lock + status + response 三 key)

- **双模式**: REJECT (默认, 防抖/拒绝二次) + REPLAY (返回首次结果, 网络重试/支付场景)
- **Idempotency-Key 头**: RFC 草案风格 (兼容 X-Request-Token)
- **幂等键要求 (2026-09-29 收紧)**: REJECT 可在缺 key 时回退 `userId + URI + body digest`;
  **REPLAY 必须由客户端显式提供 key**, 否则切面直通不拦截。自动键无法区分"网络重试"与
  "用户重复提交相同内容", 后者会拿到首次旧响应形成假成功 (2026-09-29 issue 27 决策 4)
- **Redis 三 key**:
  - `idempotency:{hash}:lock`     - SET NX EX 短锁 (30s), 防并发首次
  - `idempotency:{hash}:status`   - IN_FLIGHT / COMPLETED
  - `idempotency:{hash}:response` - JSON 响应体, REPLAY 缓存 (默认 86400s = 24h)
- **异常路径**: REPLAY 模式下业务异常 → releaseLock, 不缓存 (允许调整参数重试)
- **Redis 故障降级**: 所有 Redis 异常 warn log, 默认放行业务 (避免单点)
- **业务应用**: AuthController.register 用 REPLAY 模式

## 📨 Outbox 框架化 (M6-03, 2026-09)

**链路**: 业务方法 (`@PublishOutbox`) → `PublishOutboxAspect` (`@Around`) → `OutboxEventPublisher.publishTransactional()` → outbox_event 表 → `OutboxEventDispatcher` 异步轮询 → Spring Cloud Stream

- **`@EnableOutbox` 注解**: 业务模块加一行启用, `@Import(OutboxAutoConfiguration)` 触发装配 OutboxEventPublisher + Aspect Bean
- **`@PublishOutbox` 注解**: 业务方法标注即自动入 outbox, SpEL 提取 aggregateId (`#root.userId` 模式), 业务异常不写入 (与业务表同事务)
- **零侵入接入**: 兼容现有 `OutboxEventPublisher.publishTransactional()` 显式 API (annotation-driven / explicit 双轨)
- **异常容错**: 序列化失败 warn log + 跳过 (不阻塞业务), Redis 不可用降级 (Dispatcher 启动期容错)
- **测试覆盖**: PublishOutboxAspectTest 5 单测 (完整 publish + 默认值 + null 跳过 + 异常跳过 + 序列化失败跳过)

## 🔀 业务编排引擎 (M6-04, 2026-09)

**链路**: 流程定义 (JSON `Workflow` + `WorkflowStep` record) → `WorkflowEngine.execute()` → DAG 拓扑排序 → 同层并行 + 步骤级重试/超时 → `WorkflowResult`

- **DAG 拓扑**: BFS 分层 (level 0 = 起点), 循环依赖自动检测 (抛 IllegalStateException)
- **并行执行**: 同层 (无依赖) 步骤用 `CompletableFuture` 并行, `Executors.newFixedThreadPool(8)` 默认
- **重试**: per-step `RetryConfig` (max + interval 支持 s/ms), 指数退避 (Phase A 简化:固定间隔)
- **超时**: per-step `TimeoutConfig` + 全局 `workflow.timeoutSeconds`, 双重保护
- **失败传播**: 步骤 X 失败 → 依赖 X 的所有下游步骤标记 skipped, 不阻塞独立分支
- **Saga 补偿**: onFailure handler (Phase B 集成 `IServicePlugin`)
- **测试覆盖**: `WorkflowEngineTest` 8 单测 (单步骤 + DAG 顺序 + 重试 + 循环依赖检测 + id 唯一性 + 并行层 + 全局超时)

> **2026-10 历史清理**: `cloud-infra-codegen`（可选代码生成工具，零业务引用）已从仓库移除；相关历史记录见 git history 与 ROADMAP/CONTEXT 归档条目。

## 🏢 多租户数据隔离 (M6-01, 2026-09)

**链路**: 客户端 → Gateway `SecurityFilter.propagateTenantHeader` → 业务 `TenantContextFilter` → `TenantContext` (ThreadLocal) → `TenantSqlInterceptor` → SQL `WHERE tenant_id = ?`

- **Schema**: V14 migration (9 张业务表 + tenant_id VARCHAR(50) + 11 索引)
- **注解**: `@TenantFilter` (类级或方法级, column + rejectWhenMissing)
- **拦截器链顺序**: Pagination → Tenant → DataScope (串联而非覆盖)
- **零业务侵入**: 业务 Mapper 加类级 `@TenantFilter` 即可, 无需改 SQL
- **可选启用**: `cloud-infra.tenant.enabled=false` 时 TenantContext.hasTenant() 短路, 兼容单租户模式
- **Gateway 防御**: `SecurityFilter` 透传 `X-Tenant-Id` 时校验 `[a-zA-Z0-9\-_]{1,50}`, 阻断 CRLF 注入与过长 DoS

## 🔁 统一幂等框架 (M6-02, 2026-09)

**链路**: 客户端 → Controller (`@Idempotent`) → `IdempotentAspect` → `IdempotencyStore` → Redis (lock + status + response 三 key)

- **双模式**: REJECT (默认, 防抖/拒绝二次) + REPLAY (返回首次结果, 网络重试/支付场景)
- **Idempotency-Key 头**: RFC 草案风格 (兼容 X-Request-Token)
- **幂等键要求 (2026-09-29 收紧)**: REJECT 可在缺 key 时回退 `userId + URI + body digest`;
  **REPLAY 必须由客户端显式提供 key**, 否则切面直通不拦截。自动键无法区分"网络重试"与
  "用户重复提交相同内容", 后者会拿到首次旧响应形成假成功 (2026-09-29 issue 27 决策 4)
- **Redis 三 key**:
  - `idempotency:{hash}:lock`     - SET NX EX 短锁 (30s), 防并发首次
  - `idempotency:{hash}:status`   - IN_FLIGHT / COMPLETED
  - `idempotency:{hash}:response` - JSON 响应体, REPLAY 缓存 (默认 86400s = 24h)
- **异常路径**: REPLAY 模式下业务异常 → releaseLock, 不缓存 (允许调整参数重试)
- **Redis 故障降级**: 所有 Redis 异常 warn log, 默认放行业务 (避免单点)
- **业务应用**: AuthController.register 用 REPLAY 模式

## 📨 Outbox 框架化 (M6-03, 2026-09)

**链路**: 业务方法 (`@PublishOutbox`) → `PublishOutboxAspect` (`@Around`) → `OutboxEventPublisher.publishTransactional()` → outbox_event 表 → `OutboxEventDispatcher` 异步轮询 → Spring Cloud Stream

- **`@EnableOutbox` 注解**: 业务模块加一行启用, `@Import(OutboxAutoConfiguration)` 触发装配 OutboxEventPublisher + Aspect Bean
- **`@PublishOutbox` 注解**: 业务方法标注即自动入 outbox, SpEL 提取 aggregateId (`#root.userId` 模式), 业务异常不写入 (与业务表同事务)
- **零侵入接入**: 兼容现有 `OutboxEventPublisher.publishTransactional()` 显式 API (annotation-driven / explicit 双轨)
- **异常容错**: 序列化失败 warn log + 跳过 (不阻塞业务), Redis 不可用降级 (Dispatcher 启动期容错)
- **测试覆盖**: PublishOutboxAspectTest 5 单测 (完整 publish + 默认值 + null 跳过 + 异常跳过 + 序列化失败跳过)

## 🔀 业务编排引擎 (M6-04, 2026-09)

**链路**: 流程定义 (JSON `Workflow` + `WorkflowStep` record) → `WorkflowEngine.execute()` → DAG 拓扑排序 → 同层并行 + 步骤级重试/超时 → `WorkflowResult`

- **DAG 拓扑**: BFS 分层 (level 0 = 起点), 循环依赖自动检测 (抛 IllegalStateException)
- **并行执行**: 同层 (无依赖) 步骤用 `CompletableFuture` 并行, `Executors.newFixedThreadPool(8)` 默认
- **重试**: per-step `RetryConfig` (max + interval 支持 s/ms), 指数退避 (Phase A 简化:固定间隔)
- **超时**: per-step `TimeoutConfig` + 全局 `workflow.timeoutSeconds`, 双重保护
- **失败传播**: 步骤 X 失败 → 依赖 X 的所有下游步骤标记 skipped, 不阻塞独立分支
- **Saga 补偿**: onFailure handler (Phase B 集成 `IServicePlugin`)
- **测试覆盖**: `WorkflowEngineTest` 8 单测 (单步骤 + DAG 顺序 + 重试 + 循环依赖检测 + id 唯一性 + 并行层 + 全局超时)

## ⚡ 关键设计决策 (ADR - Architecture Decision Records)
- **决策 1**: 采用 **JDK 25 虚拟线程** $\rightarrow$ [理由: 消除传统线程池瓶颈，实现极高并发下的轻量级阻塞，提升吞吐量]
- **决策 2**: 启用 **G1GC** (堆 256m) $\rightarrow$ [理由: 小堆下 G1GC 兼顾吞吐与开销; Generational ZGC 曾被多处文档声称, 但运行态从未采用 —— 其唯一出处是一个自陈"已失效"的根 Dockerfile, 已于 2026-10-17 删除]
- **决策 3**: 采用 **Nacos** 作为配置/注册中心 $\rightarrow$ [理由: 实现动态配置秒级生效，简化微服务治理]
- **决策 4**: 部分模块（Gateway/Auth）构建 **Native Image** $\rightarrow$ [理由: 极速启动与极低内存占用，优化 K8s 弹性伸缩]

## 🔒 边界与契约 (Boundaries & Contracts)
- **内部通信**: 
    - 同步：使用 OpenFeign 遵循 REST 规范。
    - 异步：使用 RabbitMQ 实现服务间解耦。
- **外部接口**: 
    - 统一采用 RESTful 风格，返回格式为 `Result<T>` 统一响应体。
    - 身份信息通过 `X-User-Id` 等自定义 Header 在网关与下游服务间传递。
