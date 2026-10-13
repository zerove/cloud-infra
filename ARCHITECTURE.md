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
- `cloud-infra-gateway` $\rightarrow$ `cloud-infra-auth` (原因: 依赖认证中心进行 Token 校验与身份验证)

> **2026-10-13 更正**: 本文档原写 `cloud-infra-modules → cloud-infra-framework`（直接依赖）。
> 实测(pom 逐个反查)是**无任何模块直接依赖任何 `framework-*` 模块**, 全部经
> `cloud-infra-starter` 传递:
> `starter → common + framework-{security,web,mybatis,redis,tracing}`。
> 该事实有副作用且不显然 —— 从任一业务服务的 pom 里**看不到**它用了 MyBatis 拦截器、
> Redis 锁或安全过滤, 也就无法按需裁剪。且它使框架层代码更难被察觉地"继承"。

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

## ⚡ 关键设计决策 (ADR - Architecture Decision Records)
- **决策 1**: 采用 **JDK 25 虚拟线程** $\rightarrow$ [理由: 消除传统线程池瓶颈，实现极高并发下的轻量级阻塞，提升吞吐量]
- **决策 2**: 启用 **Generational ZGC** $\rightarrow$ [理由: 维持微秒级 GC 停顿，确保高并发下的低延迟响应]
- **决策 3**: 采用 **Nacos** 作为配置/注册中心 $\rightarrow$ [理由: 实现动态配置秒级生效，简化微服务治理]
- **决策 4**: 部分模块（Gateway/Auth）构建 **Native Image** $\rightarrow$ [理由: 极速启动与极低内存占用，优化 K8s 弹性伸缩]

## 🔒 边界与契约 (Boundaries & Contracts)
- **内部通信**: 
    - 同步：使用 OpenFeign 遵循 REST 规范。
    - 异步：使用 RabbitMQ 实现服务间解耦。
- **外部接口**: 
    - 统一采用 RESTful 风格，返回格式为 `Result<T>` 统一响应体。
    - 身份信息通过 `X-User-Id` 等自定义 Header 在网关与下游服务间传递。
