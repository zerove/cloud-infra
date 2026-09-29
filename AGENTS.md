# Cloud-Infra 平台代理指南 (AGENTS.md)

本文件为 OpenCode 代理提供该仓库的快速上手指南、核心指令及架构上下文。

> **当前状态**: v1.0.0 (2026-09-01, 已发布, git tag on origin) + M6 路线图 6/6 全部完成 + M7-01/03 完成 (SkyWalking + WebSocket 鉴权/TLS), M7-02 gRPC 安全边界已交付
> **M5 里程碑**: 9 个 P0/M5-01..07 issues 全部 closed, 全部关键能力经过运行时实测
> **M6 里程碑**: M6-01/02/03/04/05/06 全部 closed (WorkflowEngine + 幂等 + Outbox + OpenAPI + 业务 Dashboard)
> **M7 里程碑**: M7-01 SkyWalking 已开启, M7-03 WebSocket Phase A (Filter+Configurator 鉴权) + Phase B (TLS) 完成, M7-02 gRPC 原生框架 + 调用安全边界完成 (方法级权限待补)
> **完整交接**: [docs/HANDOFF-2026-09-01.md](docs/HANDOFF-2026-09-01.md) (v1.0.0 正式版)
> **session 总结**: `.scratch/2026-q3-roadmap/SESSION-SUMMARY-2026-09-04-final.md`

---

## 🛠️ 开发者快速指南 (Developer Operations)

### 1. 核心构建指令
- **全量构建 (跳过测试)**: `mvn clean install -DskipTests`
- **单模块构建**: `mvn clean package -pl <module-name> -am -DskipTests`
- **Native 镜像构建**: `./build-native.sh` (内存建议 12GB+，仅针对 Gateway 和 Auth)
- **Kubernetes 部署**: `./build-k8s.sh` (构建镜像并加载至 Kind 集群)

### 2. 环境启动与验证
- **快速启动本地环境**: `./deploy/up.sh` 或 `./build-local.sh`
  - 依赖 `podman-compose` (非 docker-compose)。
  - 流程：清理旧环境 $\rightarrow$ 启动基础设施 (Nacos, Redis, etc.) $\rightarrow$ 同步配置到 Nacos $\rightarrow$ 启动微服务。
  - 首次运行会自动生成 `.env` (含随机 JWT 密钥)，密钥缺失会导致服务拒绝启动。
- **基础设施清理**: `podman-compose -f deploy/compose.yaml down`
- **验证端点**:
  - 网关: `http://localhost:9000`
  - Nacos: `http://localhost:8848/nacos`
  - Prometheus: `http://localhost:9090`

### 3. 测试执行
- **执行模块所有测试**: `mvn test -pl <module-name>`
- **执行单个测试类**: `mvn test -pl <module-name> -Dtest=<TestClassName>`
- **测试底座**: `cloud-infra-test-support` 提供基于 Testcontainers 的集成测试能力 (Postgres, RabbitMQ, ES)。

---

## 🏗️ 系统架构分析 (System Architecture)

### 1. 整体设计
Cloud-Infra 是一个基于 **JDK 25 (虚拟线程)** 和 **Spring Boot 4.1.0** 构建的高并发微服务平台。

- **极致性能**: 全面启用 `spring.threads.virtual.enabled=true`，配合 **Generational ZGC** 消除线程阻塞瓶颈，保持微秒级 GC 停顿。
- **统一入口**: `cloud-infra-gateway` 处理全局认证、限流和路由，将身份信息 (`X-User-Id`, `X-User-Name`) 注入下游。
- **身份模型**: `cloud-infra-auth` 负责 JWT 签发与刷新，下游通过 `framework-security` 执行 RBAC 鉴权 (`@ss.hasPermi`)。

### 2. 模块职责地图
| 模块 | 职责 | 注意事项 |
| :--- | :--- | :--- |
| `cloud-infra-api` | Feign 客户端及共享 DTO | **禁止** 编写业务逻辑 |
| `cloud-infra-common` | 通用工具类、基类、全局异常 | 仅存放无状态工具代码，**已移除 spring-cloud-stream 依赖** |
| `cloud-infra-framework` | 内部 SDK (MyBatis, Redis, Security, Tracing) | 封装底层技术能力，`framework-security` 对 `api` 依赖 `optional` + `@ConditionalOnClass` |
| `cloud-infra-starter` | 引导与自动配置组件 | 负责框架能力的自动装配 (租户、平台属性) |
| `cloud-infra-gateway` | 统一网关 | 负责身份传播、路由拦截、内部调用签名剥离 |
| `cloud-infra-auth` | 认证中心 | 负责 Token 签发、登录锁定、验证码校验、JWT 黑名单 (logout jti) |
| `cloud-infra-modules` | 核心业务微服务 (`system`, `file`, `notification`, `search`) | 业务逻辑实现层，**所有 Controller 已补齐 @PreAuthorize** |
| `cloud-infra-codegen` | 代码生成框架 (消除 RuoYi 手工 CRUD 样板) | 含 `core` (JDK 17 编译) + `maven-plugin` (JDK 17, 自包含) 子模块;支持 Flyway DDL 解析,无需 DB 连接 |
| `cloud-infra-test-support`| 集成测试底座 | 提供 Testcontainers 配置 |

### 3. 核心业务流程
- **身份传播**: `客户端` $\rightarrow$ `Gateway (校验 JWT $\rightarrow$ 注入 Header)` $\rightarrow$ `下游服务 (HeaderAuthenticationFilter $\rightarrow$ SecurityContext)` $\rightarrow$ `@PreAuthorize` 校验。
- **配置管理**: 本地 `deploy/config/*.yaml` $\rightarrow$ `deploy/up.sh` (同步) $\rightarrow$ `Nacos` $\rightarrow$ `微服务运行时加载`。
- **异步处理**: 通过 `VirtualThreadConfig` 定义的虚拟线程执行器，确保 `@Async` 操作零池化开销。
- **消息消费**: 直接 `@RabbitListener` (concurrency 3-10) + Redis 幂等去重 (TTL 24h) + DLQ 重试。

---

## ⚠️ 代理注意事项 (Agent-Specific Guidance)

### 1. 技术栈陷阱
- **JDK 25 特性**: 优先使用虚拟线程。在编写并发代码时，避免使用 `synchronized` 块（可能导致虚拟线程钉住/pinning），建议使用 `ReentrantLock`。
- **Podman-Compose**: 本项目使用 `podman-compose` 而非 `docker-compose`，指令基本兼容但环境依赖不同。
- **Native Image**: 构建 Native 镜像需激活 `-Pnative` profile。

### 2. 开发约定
- **逻辑分层**: 业务逻辑必须在 `cloud-infra-modules` 的 `service` 层实现。
- **API 更新**: 修改接口时，必须同步更新 `cloud-infra-api` 中的 Feign 接口和 DTO，以确保跨服务类型安全。
- **配置修改**: 修改服务配置应优先修改 `deploy/config/` 下对应的 `.yaml` 文件，然后运行 `./deploy/up.sh` 同步至 Nacos。
- **密钥管理**: 所有密钥 (JWT, INTERNAL_CALL, DB, MQ, MinIO) **禁止默认值**，必须通过环境变量注入。`compose.yaml` 使用 `${VAR:?}` 强制模式，`up.sh` 自动生成 `.env`。

### 3. 关键依赖版本
- **Spring Boot**: 4.1.0
- **Spring Cloud**: 2025.1.2
- **JDK**: 25
- **数据库**: PostgreSQL 16 (实际使用) / MySQL 8.4 (驱动可配)
- **中间件**: Redis 7.2, Nacos 3.x, Elasticsearch 8.18, RabbitMQ 3.x
- **可观测性**: OpenTelemetry (OTLP 4318), Prometheus, Zipkin, Grafana

### 4. 质量基线 (2026-09-29)
- **构建**: `mvn clean verify -DskipITs=false -T 1` → BUILD SUCCESS, **809 tests, 0 fail** (2026-09-29 实测)
- **安全**: 管理端点收敛 (9301/management), 异常脱敏, JWT 黑名单 (Redis TTL 24h, JwtAuth + HeaderAuth + gateway SessionStore 三层), 文件魔数校验, P0-3b HeaderAuth blacklist 修复 (commit 370dcd1+ae06566)
- **gRPC 安全边界** (M7-02 Phase D): 内部调用 HMAC 签名 + 调用方身份 + 租户上下文三道校验, 失败统一 `UNAUTHENTICATED` 且不区分原因; `hasPermi` 在 gRPC 通道内恒为 false, 写操作仍禁止放开
- **架构**: framework-security optional api, common 无 stream 依赖, gateway 路由修复 (IdentityRequestWrapper.getHeaders + @LoadBalanced RestClient), **cloud-infra-codegen 模块新增** (core + maven-plugin 双子模块)
- **性能**: KEYS→SCAN, N+1 批量删除, 分页 LIMIT, Feign 显式超时, ZGC+512m, 基准测试 (10并发 146 RPS, p99 165ms — 2026-08-31 实测, 较旧基线 57 RPS / 1066ms 提升 2.5-6x)
- **事务**: 14+ @Transactional rollbackFor, Feign 调用移出事务, DLQ 重抛
- **部署**: compose restart/healthcheck, Helm PDB, prometheus port 9301, Seata disabled
- **代码生成**: `cloud-infra-codegen` 模块含 10+ 模板 (FreeMarker .ftl),支持 Maven 插件/CLI/编程式三种调用,基于 Flyway DDL 文件解析 (无需 DB 连接, commit 1359496)

## Agent skills

### Issue tracker

Issues live as files under `.scratch/` in this repo. See `docs/agents/issue-tracker.md`.

### Triage labels

Standard triage labels are used. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout. See `docs/agents/domain.md`.
