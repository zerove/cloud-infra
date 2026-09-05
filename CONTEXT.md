# Project Context: cloud-infra

## 🌟 Overview
`cloud-infra` is a comprehensive cloud infrastructure platform designed to provide a standardized, reusable foundation for microservices. It eliminates the need for each new service to reinvent core infrastructure concerns, ensuring consistency in monitoring, security, communication, and data access across the entire ecosystem.

## 🏗️ Architecture
The project follows a modular architecture to decouple core infrastructure capabilities from specific business logic.

### 1. Layered Structure
*   **`cloud-infra-api`**: Centralized API definitions and contracts (Feign clients, DTOs).
*   **`cloud-infra-auth`**: Specialized identity and access management service (JWT, login, captcha).
*   **`cloud-infra-gateway`**: The entry point for all external traffic, handling routing, authentication propagation, and cross-cutting concerns.
*   **`cloud-infra-common`**: The base layer providing centralized dependency management and shared utilities.
*   **`cloud-infra-framework`**: The core "engine" of the platform. It provides a set of standardized starter modules:
    *   `framework-web`: Standardized web capabilities, global exception handling, logging aspects.
    *   `framework-mybatis`: Standardized data access patterns, audit log, outbox.
    *   `framework-redis`: Standardized caching, multi-level cache, distributed locking.
    *   `framework-security`: Unified security and authorization logic (HeaderAuthenticationFilter, PermissionService).
    *   `framework-tracing`: Distributed tracing for request observability (OTel, Zipkin).
*   **`cloud-infra-starter`**: Auto-configuration aggregation (tenant, platform capabilities).
*   **`cloud-infra-modules`**: Business-specific services built on top of the framework:
    *   `system-module`: Core system management (users, roles, permissions, config, dict).
    *   `file-service`: Object storage abstraction and file management.
    *   `notification-service`: Distributed message push and notification delivery.
    *   `search-service`: Elasticsearch-based high-performance search.
*   **`cloud-infra-codegen`** (2026-08 新增): 代码生成框架, 消除 RuoYi 风格手工 CRUD 样板:
    *   `cloud-infra-codegen-core`: FreeMarker 模板 + 表元数据解析 (含 FlywayDdlParser, 无需 DB 连接).
    *   `cloud-infra-codegen-maven-plugin`: Maven 插件 (JDK 17 编译, 自包含精简模板).

### 2. Key Technical Decisions
*   **Configuration Management**: Uses **Nacos** for centralized configuration. Shared configurations (e.g., `actuator-config.yaml`) are used to ensure consistency across services.
*   **Observability**:
    *   **Actuator**: Every service exposes a management port (**9301**) with a standardized path (`/management`).
    *   **Metrics**: Integrated **Micrometer Prometheus** for standardized metrics collection.
    *   **Tracing**: **OpenTelemetry** via OTLP to collector (`otel-collector:4318`), configurable sampling rate (`TRACE_SAMPLING_PROBABILITY`).
*   **Communication**: Event-driven via **RabbitMQ** (Spring Cloud Stream removed from common). Services use `@RabbitListener` with concurrency tuning.
*   **Security**: Gateway validates JWT and propagates identity via headers (`X-User-Id`, `X-User-Name`). Downstream services use `@PreAuthorize` with `@ss.hasPermi`.
*   **Virtual Threads**: Enabled globally (`spring.threads.virtual.enabled=true`), ZGC for microsecond pause.
*   **Dependency Flow**: `common` → `framework` (optional `api`) → `modules/auth/gateway`.

## 🛠️ Tech Stack
*   **Language**: Java 25 (Virtual Threads)
*   **Framework**: Spring Boot 4.1.0, Spring Cloud 2025.1.2
*   **Config**: Nacos 3.x
*   **Observability**: Spring Boot Actuator, Micrometer, Prometheus, OpenTelemetry, Zipkin
*   **Messaging**: RabbitMQ (direct consumer)
*   **Database/Cache**: PostgreSQL 16, Redis 7.2, MyBatis Plus 3.5.17
*   **Container**: Podman, Kind, Helm

## 📖 How to Use This Repository
Developers should start by looking at the `docs/superpowers` directory for specific implementation plans and designs before modifying core framework components.

## ✅ Recent Optimizations (2026-08)
*   Security hardening: removed all hardcoded secrets, JWT weak key blacklist, management endpoint convergence.
*   Architecture: framework-security optional api, removed spring-cloud-stream from common.
*   Performance: KEYS→SCAN, N+1 fixes, pagination LIMIT, RabbitListener concurrency, Feign timeouts, ZGC+512m.
*   Quality: @Transactional rollbackFor, Feign out of transactions, idempotent consumers, DLQ config, StreamingResponseBody.
*   Deployment: compose restart/healthcheck, Helm PDB, prometheus port 9301, dirty file cleanup.
*   Shared config: actuator-config.yaml with OTLP 4318, TRACE_SAMPLING_PROBABILITY, feign timeouts.

## ✅ Recent Optimizations (2026-09) — v1.0.0 候选
*   **P0-3b 端到端 401 验证** (commit 370dcd1 + ae06566): JWT blacklist 在直连业务模块场景下实测返回 HTTP 401 (含 X-Blacklist-Jti 响应头).
*   **Caffeine ClassLoader 死锁修复** (commit ef0d69d): cloud-system 内存 768m→2g + JAVA_OPTS 加 `--enable-native-access=ALL-UNNAMED`, 解决 Spring Boot 4.1 + Caffeine 3.x + JDK 25 ClassLoader 死锁.
*   **cloud-infra-codegen 模块新增** (commit b9a2a84..1359496): 10+ FreeMarker 模板 + FlywayDdlParser (无需 DB 连接), 15 张表自动生成测试通过.
*   **M5-04b SQL 模板 PG 化** (commit 8b6b6de): MySQL 语法 → PostgreSQL 16 风格 (BIGSERIAL / COMMENT ON / IF NOT EXISTS).
*   **M5-04c 模板增强** (commit 0ef0005): VO 加 @Schema 注解, UpdateDTO @Size 256→255.
*   **M5-05 FlywayDdlParser 端到端** (commit 2ff8d50): 15 表自动解析 + 智能识别 @TableLogic + SMALLINT/VARCHAR(N)/UNIQUE/DEFAULT.
*   **M5-03 业务告警指标 AOP 化** (commit 8430811): @BusinessMetric 注解 + AOP 切面, Counter 按需注册, 11 告警规则中 2 条业务类 (`auth_login_attempts_total` + `file_upload_failures_total`) 真正落地.

## ✅ Recent Optimizations (2026-09) — M6-01 多租户数据隔离
*   **M6-01 Phase A 基础设施** (commit db90f52): V14 migration (9 张业务表加 tenant_id 列 + 11 索引) + `@TenantFilter` 注解 + `TenantSqlInterceptor` 自动拦截器 + 12 单元测试.
*   **M6-01 Phase B 业务回填** (commit 1698a7e): 9 个核心 Mapper 加类级 `@TenantFilter` (SysPost/User/Dept/Role/Menu/Permission/File/Notification/Preference) + file-service schema.sql 同步.
*   **M6-01 Phase C Gateway 集成** (commit pending): `SecurityFilter.propagateTenantHeader` 透传 `X-Tenant-Id` (含防御性 [a-zA-Z0-9\-_] 校验) + `TenantContextFilterTest` 9 个端到端测试覆盖 header/query/requireTenant/finally 清理.
*   **架构影响**: 业务 Mapper 加类级注解即可启用多租户, 零业务侵入. interceptor 链顺序 Pagination → Tenant → DataScope, 串联而非覆盖.
*   **测试矩阵**: common (14) + framework-mybatis (16) + system-module (77) + file-service (36) + notification-service (13) = 156 测试通过 (Phase A/B/C 合计 +9).

## ✅ Recent Optimizations (2026-09) — M6-02 统一幂等框架
*   **双模式幂等注解** (commit 1945dd4): `@Idempotent.Mode` 加 REJECT (防抖, 默认) + REPLAY (返回首次结果, PRD 核心要求); `keyHeader` 默认 `Idempotency-Key` (RFC 草案, 兼容 X-Request-Token).
*   **Redis JSON 响应缓存**: `IdempotencyEntry` POJO + `IdempotencyStore` 三 key 设计 (`lock`/`status`/`response`), IN_FLIGHT/COMPLETED 状态机, Redis 异常自动降级.
*   **IdempotentAspect 重写**: `@Around` 包裹, REJECT 模式 SET NX EX 抛异常, REPLAY 模式读缓存命中即返回, 业务异常 releaseLock 不缓存.
*   **Testcontainers 集成测试**: `IdempotencyStoreIT` 5 测试真实 Redis 7-alpine (SET NX 互斥 + JSON 往返 + 多 key 隔离).
*   **业务应用**: `AuthController.register` 改 REPLAY 模式 (24h TTL, 网络重试场景).
*   **测试矩阵**: framework-web 单测 30 + 集成 5 = 35 (新增 22), 全模块合计 **218 测试通过**.

## ✅ Recent Optimizations (2026-09) — M6-03 Outbox 框架化
*   **@EnableOutbox 注解** (commit 7c5f8a3): 业务模块加一行 `@EnableOutbox` 即启用 Outbox, `@Import` 触发 `OutboxAutoConfiguration` 装配 `OutboxEventPublisher` Bean.
*   **@PublishOutbox 注解 + AOP**: `PublishOutboxAspect` 用 `@Around` 拦截带注解的方法, SpEL 提取 aggregateId (`#root.userId` 模式), 事务提交后自动入 outbox_event 表. 业务异常不写入, 序列化失败不阻塞业务.
*   **零业务侵入**: 兼容现有 `OutboxEventPublisher.publishTransactional()` 显式 API, 业务模块可选择注解驱动或显式调用.
*   **测试**: `PublishOutboxAspectTest` 5 测试覆盖 (完整 publish + 默认值 + null 返回跳过 + 业务异常跳过 + 序列化失败跳过).
*   **测试矩阵**: framework-mybatis 单测 21 (新增 5 Outbox Aspect), 全模块合计 **223 测试通过**.

## ✅ Recent Optimizations (2026-09) — M6-04 业务编排引擎 Phase A
*   **流程定义** (commit pending): `Workflow`/`WorkflowStep`/`RetryConfig`/`TimeoutConfig` record, Jackson 序列化友好, JSON 步骤定义 + DAG 依赖.
*   **引擎核心**: `WorkflowEngine` 同层并行执行 + BFS 拓扑排序 (无环 DAG) + 步骤级重试 + 超时 + 全局超时 + 失败传播 (依赖步骤跳过).
*   **配套**: `WorkflowContext` (trigger + variables) + `WorkflowResult` (success/outputs/errors/skipped/durationMs).
*   **测试**: `WorkflowEngineTest` 8 测试覆盖 (单步骤 + DAG 顺序 + 重试配置 + 失败传播 + 循环依赖检测 + 步骤 id 唯一性 + 并行层 + 全局超时).
*   **测试矩阵**: common 33 (新增 8), 全模块合计 **231 测试通过**.

## ✅ Recent Optimizations (2026-09) — M6-04 业务编排引擎 Phase B
*   **PluginStepHandler 集成** (commit pending): `WorkflowStepHandler` 接口 + `WorkflowStepHandlerRegistry` (启动自动扫描 @Component Bean, 按 "BeanName.methodName" 索引).
*   **WorkflowEngine 注入 handlerInvoker**: `BiFunction<String, WorkflowContext, Object>` 委托, Phase A 用默认空实现, Phase B 接 Registry 自动查找.
*   **Spring 集成**: `WorkflowEngineAutoConfiguration` (@ConditionalOnBean Registry) 注册 WorkflowEngine Bean, 业务模块用 `@Autowired WorkflowEngine` 即可.
*   **测试**: `WorkflowStepHandlerIntegrationTest` 4 测试 (handler 真正调用 + 未注册异常 + 重试机制 + 输出保存).
*   **测试矩阵**: framework-web 39 (新增 4 + 原 35), 全模块合计 **246 测试通过**.