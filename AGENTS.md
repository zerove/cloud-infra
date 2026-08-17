# Cloud-Infra 平台代理指南 (AGENTS.md)

本文件为 OpenCode 代理提供该仓库的快速上手指南、核心指令及架构上下文。

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
Cloud-Infra 是一个基于 **JDK 25 (虚拟线程)** 和 **Spring Boot 4** 构建的高并发微服务平台。

- **极致性能**: 全面启用 `spring.threads.virtual.enabled=true`，配合 **Generational ZGC** 消除线程阻塞瓶颈，保持微秒级 GC 停顿。
- **统一入口**: `cloud-infra-gateway` 处理全局认证、限流和路由，将身份信息 (`X-User-Id`, `X-User-Name`) 注入下游。
- **身份模型**: `cloud-infra-auth` 负责 JWT 签发与刷新，下游通过 `framework-security` 执行 RBAC 鉴权。

### 2. 模块职责地图
| 模块 | 职责 | 注意事项 |
| :--- | :--- | :--- |
| `cloud-infra-api` | Feign 客户端及共享 DTO | **禁止** 编写业务逻辑 |
| `cloud-infra-common` | 通用工具类、基类、全局异常 | 仅存放无状态工具代码 |
| `cloud-infra-framework` | 内部 SDK (MyBatis, Redis, Security, Tracing) | 封装底层技术能力，供各模块依赖 |
| `cloud-infra-starter` | 引导与自动配置组件 | 负责框架能力的自动装配 |
| `cloud-infra-gateway` | 统一网关 | 负责身份传播与路由拦截 |
| `cloud-infra-auth` | 认证中心 | 负责 Token 签发、登录锁定、验证码校验 |
| `cloud-infra-modules` | 核心业务微服务 (`system`, `file`, `notification`, `search`) | 业务逻辑实现层 |
| `cloud-infra-test-support`| 集成测试底座 | 提供 Testcontainers 配置 |

### 3. 核心业务流程
- **身份传播**: `客户端` $\rightarrow$ `Gateway (校验 JWT $\rightarrow$ 注入 Header)` $\rightarrow$ `下游服务 (解析 Header $\rightarrow$ SecurityContext)` $\rightarrow$ `@PreAuthorize` 校验。
- **配置管理**: 本地 `deploy/config/*.yaml` $\rightarrow$ `deploy/up.sh` (同步) $\rightarrow$ `Nacos` $\rightarrow$ `微服务运行时加载`。
- **异步处理**: 通过 `VirtualThreadConfig` 定义的虚拟线程执行器，确保 `@Async` 操作零池化开销。

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

### 3. 关键依赖
- **Spring Boot**: 4.1.0
- **Spring Cloud**: 2025.1.2
- **JDK**: 25
- **数据库**: PostgreSQL 16 / MySQL 8.4
- **中间件**: Redis 7.2, Nacos, Elasticsearch, RabbitMQ

## Agent skills

### Issue tracker

Issues live as files under `.scratch/` in this repo. See `docs/agents/issue-tracker.md`.

### Triage labels

Standard triage labels are used. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout. See `docs/agents/domain.md`.

