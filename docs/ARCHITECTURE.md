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

- **链路追踪**: Micrometer Tracing $ightarrow$ Zipkin/Jaeger (可选)。
- **指标监控**: Micrometer $ightarrow$ Prometheus $ightarrow$ Grafana。
- **健康检查**: Spring Boot Actuator 提供 `/health` 和 `/info` 端点，用于 Kubernetes/Podman 编排。
