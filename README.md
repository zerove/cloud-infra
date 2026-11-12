# 云基础设施平台 (Cloud-Infra)

高性能、企业级云基础设施平台，基于最新 Java 生态构建。专为极致并发、分布式可扩展性和开发者生产力而设计。

## 📌 v1.0.0 (2026-09-01)

![Status](https://img.shields.io/badge/status-v1.0.0-brightgreen) ![Java](https://img.shields.io/badge/Java-25-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-brightgreen) ![Tests](https://img.shields.io/badge/tests-504%20passing-success) ![ADRs](https://img.shields.io/badge/ADRs-11-blueviolet) ![ROADMAP](https://img.shields.io/badge/ROADMAP-1%2F6%20%E8%A2%AB%E4%BD%BF%E7%94%A8-blueviolet)

> ⚠️ **进度口径说明** (2026-09-30): 徽章中的 `ROADMAP 1/6` 按项目级完成定义统计,
> 即**被真实业务使用的能力数**。若按传统“条目打勾”口径计, M6/M7 看起来接近全绿 ——
> 但那些条目的代码并无业务引用。**组件自测通过不等于交付。**
> 详见 [ROADMAP.md](ROADMAP.md) “按被使用标准重算”。

**v1.0.0 关键变更** (相对 v1.0.0-rc1):
- ⛔ **cloud-infra-codegen 模块已移除**: 历史上曾作为可选工具存在（仅 `sys_post` 一表验证，零业务使用），后续清理中已从仓库移除，不再作为可用能力文档化
- ✅ **P0-3b JWT blacklist 端到端 401 实测通过** (HTTP 401 + Redis blacklist TTL 24h)
- ✅ **Caffeine ClassLoader 死锁修复**: cloud-system 内存 768m→2g + `--enable-native-access=ALL-UNNAMED`
- ✅ **业务告警指标 AOP 化** (`@BusinessMetric` 注解): auth.login.attempts + file.upload.failures 真正落地
- ✅ **AGENTS.md 同步** (文档与仓库状态对齐)

**完整状态**: [HANDOFF-2026-09-01.md](docs/HANDOFF-2026-09-01.md) · 26 个新 commits · 9 P0/M5 issues closed · 11 Prometheus 告警 (2 业务类运行时实测) · 性能基线见下方「性能基线」(口径: 网关入口路径, 非业务链路)

**v1.0.0 零信任发布**: 6 个质量门全部通过, git tag `e6b786d` on origin, 0 commit drift. 详见 [docs/releases/v1.0.0.md](docs/releases/v1.0.0.md)

**快速开始**:
```bash
./deploy/up.sh              # 启动基础设施 + 同步 Nacos 配置
bash e2e-test.sh            # 6/6 业务断言 PASS
./deploy/up.sh --check      # 0 配置漂移
```

## 🚀 技术栈

- **核心**: Spring Boot 4.1.0 | JDK 25 (启用虚拟线程 Virtual Threads)
- **云生态**: Spring Cloud 2025.1.2 | Spring Cloud Alibaba 2025.1.0.0
- **注册与配置**: Nacos
- **持久化**: MyBatis Plus 3.5.17 | PostgreSQL 16 | MySQL 8.4
- **缓存与分布**: Redisson 4.7.0 | Redis 7.2
- **安全**: Spring Security | JWT (jjwt 0.13.0) | RBAC (基于角色的访问控制)
- **可观测性**: Micrometer Tracing | Prometheus | Actuator
- **基础设施**: Podman/Docker | Maven 3.9+

## 🏗️ 架构概览

系统采用微服务架构，职责分明：

- **`cloud-infra-gateway`**: 统一入口。处理全局认证、限流和请求路由。
- **`cloud-infra-auth`**: 集中式身份提供者。管理令牌发放、校验和会话管理。
- **`cloud-infra-framework`**: 内部 SDK。提供安全过滤器、Redis 封装和链路追踪配置等共享能力。
- **`cloud-infra-modules`**:
    - `system-module`: 管理核心。负责用户、角色、权限、部门及系统配置。
    - `file-service`: 抽象对象存储与文件管理。
    - `notification-service`: 分布式消息推送与通知交付。
    - `search-service`: 基于 Elasticsearch 的高性能搜索。
- **`cloud-infra-api`**: 共享 DTO 和 Feign 客户端，确保服务间通信的类型安全。
- **`cloud-infra-common`**: 基础工具类和通用基类。

## 📈 可观测性

- **链路追踪**: 每个请求在网关处被分配一个 `traceId`，并通过 Micrometer Tracing 在所有微服务间传播。
- **监控**: Prometheus 抓取各服务的 `/actuator/prometheus` 端点，提供实时健康状态和性能指标。
- **日志**: 标准化日志格式，包含 `traceId` 以实现分布式日志关联。

## 🛡️ 关键修复记录 (2026-08)

- **Gateway 路由修复**: 修复 `IdentityRequestWrapper.getHeaders()` 缺失导致的 NPE，修正 `RestClient.Builder` 添加 `@LoadBalanced` 支持 `lb://` 服务发现路由。
- **配置校验优化**: ConfigValidationAutoConfiguration 新增条件注解，测试环境自动禁用，避免启动噪音。
- **系统模块覆盖率调整**: 降低整体覆盖率门槛至 15%，核心 service.impl 保持 80% 门槛。

## 📊 性能基线 (2026-08 实测)

| 指标 | 值 |
|------|-----|
| 模式 | JVM (JDK 25 + 虚拟线程) |
| 并发 | 10 |
| 启动时间 | 68.8s |
| RSS 内存 | 566MB |
| 吞吐量 | 57 RPS |
| 平均延迟 | 131ms |
| p50 延迟 | 107.5ms |
| p95 延迟 | 214ms |
| p99 延迟 | 1066ms |
| 最大延迟 | 1163ms |

> ⚠️ **口径说明（必读）**: 端点是 `/v3/api-docs` —— 网关自己生成的**静态 OpenAPI JSON**，
> **不触达任何业务逻辑**。因此上表衡量的是"网关入口路径"（过滤器链 + 路由 + 响应），
> **不是业务吞吐或业务延迟**，不能据此推断业务性能。
> 要测业务链路需起全栈后用 `./benchmark.sh --endpoint <业务路径>` 重测。
> 另：本表为 2026-08 的基线；此处不再并列更晚的"优化后"数字，
> 因为两者若非同端点同并发比较，数字变小只说明测的东西变了，不说明变快了。

## 🛠️ 快速开始

### 前置条件
- JDK 25
- Maven 3.9+
- Podman 或 Docker

### 本地开发
1. **构建项目**:
   ```bash
   mvn clean install -DskipTests
   ```
2. **部署基础设施**:
   ```bash
   cd deploy
   ./up.sh
   ```
3. **访问平台**:
   - 网关: `http://localhost:9000`
   - Nacos: `http://localhost:8848/nacos`
   - Prometheus: `http://localhost:9090`

## 🔐 安全模型

系统采用 **分布式令牌认证模型**:
1. **认证**: 客户端向 `cloud-infra-auth` 请求令牌。
2. **校验**: `cloud-infra-gateway` 校验 JWT 并提取用户身份。
3. **传播**: 网关将 `X-User-Id` 和 `X-User-Name` 请求头注入到请求中，随后路由至下游服务。
4. **授权**: 下游服务利用注入的请求头和 `PermissionService` 通过 `@PreAuthorize` 或手动检查执行 RBAC 校验。

## 📈 可观测性

- **链路追踪**: 每个请求在网关处被分配一个 `traceId`，并通过 Micrometer Tracing 在所有微服务间传播。
- **监控**: Prometheus 抓取各服务的 `/actuator/prometheus` 端点，提供实时健康状态和性能指标。
- **日志**: 标准化日志格式，包含 `traceId` 以实现分布式日志关联。
