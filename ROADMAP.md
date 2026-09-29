# Project Roadmap: cloud-infra

This document serves as the master tracking list for the `cloud-infra` project, aggregating tasks from various implementation plans and architectural goals.

> 2026-08-25 校准：里程碑状态与代码实况对齐；新增 Milestone 4（安全与可靠性强化），
> 详细分析见 `docs/OPTIMIZATION_PLAN_2026_08.md`。

## 🚦 Status Legend
- 🔴 **To Do**: Not yet started.
- 🟡 **In Progress**: Implementation underway.
- 🟢 **Completed**: Implemented **and used by at least one business module**, with runtime evidence.
- ⚪ **Built, Unused**: Code exists and is self-tested, but **no business module references it**. Not a delivered capability; do not build on top of it without first finding a real use case.
- ⛔ **Frozen**: Deliberately paused. Do not add to it until the stated precondition is met.

> **2026-09-29 完成判定标准修正**: 过去用“组件自身测试通过”作为完成依据, 导致
> M6 中 4 项能力被标为 `🟢 Completed` 但业务零引用。现改为**必须有业务模块引用 + 运行时证据**。
> 自建组件的单元测试只证明它能自我运转, 不证明它有价值。

---

## 🔭 Strategic Themes

### 1. Observability & Management (Standardization)
**Goal:** Ensure every service in the ecosystem is equally observable and manageable.
- [x] 🟢 **Actuator Engineering**: Standardize management port (9301) and path (`/management`) across all services. → *See `plans/2026-04-21-actuator-engineering.md`*
- [x] 🟢 **Metrics Aggregation**: Implement standardized custom metrics registration in `framework-web`. → *See `plans/2026-04-25-monitoring-implementation.md`*
- [x] 🟢 **Centralized Log Aggregation**: Integrate ELK/Loki across all modules.
- [x] 🟢 **Env Endpoint Sanitization**: `/management/env` 脱敏与暴露面收敛（commit ab5baa5, S1 已完成）。

### 2. Reliability & Event-Driven Architecture
**Goal:** Improve system resilience and decouple service interactions.
- [x] 🟢 **TxMessage Retry Refactor**: Transition `TxMessageRetryTask` to use `StreamBridge` for asynchronous dispatch. → *See `plans/2026-05-08-tx-message-retry-refactor-plan.md`*
- [x] 🟢 **Dead Letter Queue (DLQ) Implementation**: Standardize error handling for failed stream messages.
- [x] 🟢 **Distributed Tracing Integration**: Full end-to-end trace verification for cross-module calls.
- [x] 🟢 **Outbox 并发派发行锁**（R1 已完成, commit 5887c85 原子性 + 多实例派发）。
- [x] 🟢 **Outbox 原子性修复**（R2 已完成, 同事务同步写入）。
- [x] 🟢 **集成测试从 0 到 1**（R3 已完成, commit a6c61ad CI 接入 IT + f0cb48a file-service 主链路 IT）。

### 3. Framework Core & Developer Experience
**Goal:** Reduce friction for developers adding new services.
- [x] 🟢 **High-Level Context**: Establish `CONTEXT.md` for project onboarding.
- [x] 🟢 **Ubiquitous Language**: Define a canonical glossary in `UBIQUITOUS_LANGUAGE.md`.
- [x] 🟢 **Project Roadmap**: Create this master tracking document.
- [x] 🟢 **Developer Guide**: Create a "How-to" guide for implementing new modules using the framework.
- [x] 🟢 **文档版本校准**（H3 已完成, commit a6c61ad CI 文档版本门禁 + README/CONTEXT.md 校正）。

### 4. Security Hardening (2026-08)
**Goal:** Close identity/cache/credential gaps found in the 2026-08 audit.
- [x] 🟢 **GraalVM Native 网关与认证服务交付**：网关 1.37s / auth 11.6s 启动，健康验证通过（提交链 f1e3e7a…07740fd）。
- [x] 🟢 **权限缓存失效链路**（S2 已完成, commit 39ac527 + 6c55331 角色删除补齐）。
- [x] 🟢 **令牌吊销机制**（S4 已完成, commit ae6249f 用户禁用/删除/改密后吊销存量会话, JWT 黑名单 Redis TTL）。
- [x] 🟢 **MQ 凭据治理**（S3 已完成, deploy/compose.yaml 用 ${RABBITMQ_USER:?} 强制占位符）。
- [x] 🟢 **插件系统安全模型**（commit 7ac18f7 ADR-010 实施, dev 启用, prod 禁用）。

### 5. Code Generation & Business Alert (2026-09, v1.0.0 候选)
**Goal:** ~~消除 RuoYi 手工 CRUD 样板~~ + 业务告警闭环。
codegen 部分已于 2026-09-29 降级为 `⚪` 可选开发工具 (仅 1 张表验证过), 不再作为交付目标推进。
- [x] ⚪ **cloud-infra-codegen 模块** (commit b9a2a84..1359496): 10+ FreeMarker 模板 (entity/mapper/dto/vo/converter/service/impl/controller/sql) + FlywayDdlParser (无需 DB 连接). 2464 行 + 26 项自测
  - ⚪ **2026-09-29 降级为可选工具**: 目标“消除 RuoYi 手工 CRUD 样板”仅在 `sys_post` 一张表上验证过, **其余业务表均为手写**。判定为可选开发工具, **不再占用路线图名额、不再新增模板**。若未来真实表迁移需求出现, 再评估投入
- [x] 🟢 **codegen 模板 PG 化** (commit 8b6b6de): MySQL → PostgreSQL 16 风格 (BIGSERIAL, COMMENT ON, IF NOT EXISTS) + 回归测试.
- [x] 🟢 **VO @Schema + UpdateDTO @Size 255** (commit 0ef0005): Swagger 字段描述完整 + 校验合理.
- [x] 🟢 **FlywayDdlParser 端到端验证** (commit 2ff8d50): 15 表自动解析, 智能识别 SMALLINT/VARCHAR(N)/UNIQUE/DEFAULT/@TableLogic.
- [x] 🟢 **M5-03 业务告警指标接入** (commit 8430811): @BusinessMetric 注解 + AOP 切面, auth.login.attempts{result=success|failure} + file.upload.failures{} 真正落地, 11 告警规则中 2 条业务类 (LoginFailureRateHigh + FileUploadFailureHigh) 可触发.
- [x] 🟢 **P0-3b 端到端 401 实测** (commit 370dcd1 + ae06566): JWT blacklist 直连业务模块实测 HTTP 401.
- [x] 🟢 **deploy compose 内存修复** (commit ef0d69d): cloud-system 768m→2g + --enable-native-access=ALL-UNNAMED, 解决 Caffeine ClassLoader 死锁.

### 5.1 v1.0.0 Release Tag (2026-09-01, immutable)
- **tag 位置**: `e6b786d` (SESSION-SUMMARY 同步, 含 M5-03 运行时验证报告 2c88ea2)
- **远端可见**: https://github.com/zerove/cloud-infra/releases/tag/v1.0.0
- **ahead origin/master**: 0 commits
- **质量门后修正** (commit 9760bfb + 45e14b8): AGENTS.md / ARCHITECTURE.md 标注 v1.0.0 + issue 描述修正 (stale 内容), 未 force-push tag (符合 immutable release 实践)
- **未来补丁**: 应该是 v1.0.1, 不动 v1.0.0 tag

---

## 📅 Timeline & Milestones

### Milestone 1: Foundation & Alignment
*Focus: Documentation, Core Framework standardization, and basic observability.*
- [x] Project Context defined.
- [x] Ubiquitous Language established.
- [x] Actuator standardization applied to all core services (9301 + shared actuator-config.yaml).
- [x] Framework-web metrics implemented.

### Milestone 2: Reliability Hardening
*Focus: Message reliability, transaction consistency, and error recovery.*
- [x] TxMessage retry logic refactored (StreamBridge).
- [x] Seata removed from system-module（本地事务+补偿，ADR 记录于 CONTEXT.md）。分布式事务验证不再适用。
- [x] Outbox 派发并发安全与原子性（R1/R2 已完成, commit 5887c85 + a6c61ad）。
- [x] 集成测试覆盖主链路（R3 已完成, CI 接入 IT, file/search 业务主链路 IT 全绿）。

### Milestone 3: Scale & Optimization
*Focus: Performance tuning, advanced observability, and developer self-service.*
- [x] Developer Guide published (`docs/DEVELOPER_GUIDE.md`)。
- [x] Native Image 性能基线建立（F1 已完成, commit 0b119ac p99 5188→970ms, native benchmark 在 benchmark/results/）。
- [x] JVM 启动时间剖析（F3 已完成, commit 4e33958 启动 66.7s→25.5s, Sentinel 5 数据源共享化 34.5s→0.29s）。
- [x] Advanced alerting rules configured (Prometheus)。已完成 11 条规则: 基础可用性/JVM/HTTP/连接池/日志 7 项 + SLO (可用性/p99 预算) 2 项 + 业务 (登录失败/文件上传) 2 项, 关键告警 critical tier 升级。详见 deploy/monitoring/prometheus-rules/cloud-infra-alerts.yml。
- [x] SLO dashboard (Grafana) 已落地: deploy/monitoring/grafana/dashboards/cloud-infra-slo.json, 9 panel 覆盖可用性/P99 延迟/RPS/错误预算燃烧率/5xx 错误率/延迟分布/JVM 堆/HikariCP 池/活跃告警, 模板变量 application 多选, commit 0cec295。

### Milestone 4: Security & Hygiene (Current, 2026-08)
*Focus: Audit remediation from OPTIMIZATION_PLAN P0/P3.*
- [x] 仓库卫生：class 编译产物出库、根目录日志清理、gitignore 加固。
- [x] S1-S4 安全缺陷修复全部完成（env 脱敏/缓存失效/MQ 凭据/令牌吊销, commit ae6249f/39ac527/6c55331/ab5baa5）。
- [x] 文档版本漂移一次性校正（README/CONTEXT.md 已对齐 pom, CI 加 docs version gate, commit a6c61ad）。
- [x] E2E 测试真实化（e2e-test.sh 6 场景真实断言, 替换 mock token, commit e1b2a2e H5）。
- [x] 插件系统安全模型（commit 7ac18f7 ADR-010, dev 启用, prod 禁用, HotDeploymentWatcher @ConditionalOnProperty）。

### Milestone 5: v1.0.0 Production-Ready (2026-09, 已发布)
*Focus: P0 修复 + codegen 价值验证 + 业务告警闭环.*
- [x] ⚪ **cloud-infra-codegen 模块** (commit b9a2a84..1359496, 8 commits / 3660 行) — 2026-09-29 降级为可选开发工具, 不再占路线图名额
- [x] 🟢 **P0-3b 端到端 401 实测** (commit 5a82318): JWT blacklist 运行时验证
- [x] 🟢 **codegen 灰度迁移 sys_post** (commit 0ccdf52): 验证 codegen 输出与手写代码语义一致
- [x] 🟢 **M5-03 业务告警 AOP 化** (commit 8430811 + 2c88ea2): @BusinessMetric 注解 + 运行时 Counter 7.0 实测
- [x] 🟢 **v1.0.0 tag** (tag at `e6b786d`, 远端可见, ahead=0, immutable)

### Milestone 6: Multi-Tenant Isolation (2026-09, 已交付 + 未启用部分复核)
*Focus: 业务 SQL 跨租户隔离, 零业务侵入接入.*
- [x] 🟢 **M6-01 Phase A 基础设施** (commit db90f52): V14 schema (9 表 tenant_id + 11 索引) + @TenantFilter 注解 + TenantSqlInterceptor + 12 单元测试
- [x] 🟢 **M6-01 Phase B 业务回填** (commit 1698a7e): 9 个核心 Mapper 加 @TenantFilter (SysPost/User/Dept/Role/Menu/Permission/File/Notification/Preference) + file-service schema.sql tenant_id
- [x] 🟢 **M6-01 Phase C Gateway 集成** (commit 846258c): SecurityFilter.propagateTenantHeader 透传 X-Tenant-Id + TenantContextFilterTest 9 端到端测试
- [x] 🟢 **M6-01 Phase D 端到端运行时验证** (commit 17ee31a): 容器起 PG/Redis, 跨租户查询隔离实测完成 (4 sys_post + 1 sys_user 全部 default 租户兼容, tenant-X 查询 0 行隔离)
- [x] ⚪ **M6-02 统一幂等框架** (commit 1945dd4): @Idempotent 双模式 (REJECT/REPLAY) + Idempotency-Key RFC 头 + IdempotencyStore (Redis JSON 缓存) + IdempotentAspect 重写 + 17 单元测试 + 5 Testcontainers 集成测试
  - ⚪ **2026-09-29 复核与治理**: 唯一使用点 `AuthController.register` 已移除注解。排查确认全仓无任何客户端发送 `Idempotency-Key`, 且原自动键 (`anon + URI + body 摘要`) 会在 24h 内把"用户重复提交"重放成"成功"（假成功）。框架语义已收紧：**REPLAY 必须客户端显式提供键**，自动键仅可用于 REJECT 防抖。详见 issue 27 决策 4
- [x] ⚪ **M6-03 Outbox 框架化** (commit 7c5f8a3): @EnableOutbox 注解 + OutboxAutoConfiguration + @PublishOutbox 注解 + AOP (SpEL aggregateId, 事务提交后入表) + 5 单元测试
  - ⚪ **2026-09-29 复核**: **业务模块 0 引用** (767 行)。Outbox 模式本身正确, 但无任何业务接入。需先找到真实的一致性敏感场景再启用
- [x] ⚪ **M6-04 业务编排引擎** (commit 267fd77 + cab11fb + 6d363c9): WorkflowEngine (DAG 分层 + 并行 + 重试 + 步骤超时 + Saga 补偿) + WorkflowStepHandler SPI + 启动扫描注册
  - ⚪ **2026-09-29 复核**: **业务模块 0 引用** (764 行, 含 17 项自测)。Saga 编排有真实价值, 但引擎无使用方。保留代码, 暂停投入
- [x] ⚪ **M6-05 统一 OpenAPI** (commit 6e6a19e + a44df82 + d26d01e): @EnableOpenApi + OpenApiExporter 启动期 dump + build-openapi.sh 聚合
  - ⚪ **2026-09-29 复核**: **@EnableOpenApi 业务 0 处, OpenApiExporter 装配 0 处**。聚合脚本存在但无服务导出可供聚合
- [x] ⚪ **M6-06 业务告警 Dashboard** (commit 9ddd7d0): cloud-infra-business.json 6 panels
  - ⚪ **2026-09-29 复核**: 6 个 panel 中, 仅 `auth.login.attempts` / `file.upload.failures` 有对应 `@BusinessMetric` 产生点, 其余面板数据源缺失
- [x] 🟢 **M7-01 SkyWalking 默认开启** (commit 937e3de): 6 Dockerfile `COPY --from=apache/skywalking-java-agent` + `ENTRYPOINT ["/app/agent-entrypoint.sh"]` 注入 `-javaagent` + compose 6 业务模块加 SW env + depends_on cloud-skywalking-oap
  - ✅ **2026-09-29 复核**: agent 确实打进镜像且 entrypoint 生效, 非仅设环境变量
- [x] 🟢 **M6-01 Phase D Dockerfile 路径修复** (commit 0f1e169, 验证 commit 66ec01a): 6 Dockerfile `COPY deploy/...` 路径 + compose build context `..` 修复, `./deploy/up.sh --full` 可成功 build 镜像
- [x] 🟢 **M7-03 WebSocket 鉴权改造** (commits 6c20099 + 6c30458): Phase A Filter + Configurator 协同 (解决 tyrus 2.2 + jwaa 2.2 HandshakeResponse 缺失) + 7 测试; Phase B TLS 配置 (PKCS12 keystore + Nginx wss:// 终止 + 证书生成脚本); 文档 ARCHITECTURE.md §5
- [ ] **M7-02 gRPC 支持** (issue 24, in progress)
  - [x] 🟢 **M7-02 Phase A**: framework-grpc 模块基础设施 (commit 8ace1d8): EnableGrpcServer 注解 + CloudInfraGrpcProperties 配置 + GrpcServerAutoConfiguration + EchoService proto
  - [x] 🟢 **M7-02 Phase B**: EchoServiceImpl 业务逻辑 + 5 单元测试 (commit 3dc6649): Echo/EchoWithMetadata/Health RPC, count 安全上限, 5/5 测试通过
  - [x] ⚠️ **M7-02 Phase C**: LogNet optional + ConditionalOnClass 守护 (commit 774f10c): LogNet 3.0.1/5.2.0 基于 Spring Boot 3.x, 与 Spring Boot 4 EnvironmentPostProcessor 机制冲突; 后改为原生实现 (commit 8bcbe63)
  - [x] 🟢 **M7-02 Phase D 调用安全边界**: 内部调用 HMAC 签名 + 调用方身份 + 租户上下文。`PlatformCaller`/`CallerContextBinder` (common) + `GrpcSecurityInterceptor`/`GrpcCallerContext` (framework-grpc) + `SpringSecurityCallerContextBinder` (framework-security 反向装配)。12 + 6 + 1 项测试, 含真实 TLS 网络验收
  - [ ] ⚠️ **M7-02 Phase E**: 方法级权限映射 + 数据范围过滤 + 第二业务模块客户端 + HTTP→gRPC 转换。当前 `hasPermi` 在 gRPC 通道内恒为 false, 写操作禁止放开
- ⛔ **未来 M7+ (CDC / 模板市场 / VS Code 插件) 已冻结** (2026-09-29)

  冻结原因: 在 M6/M7 已有 4 项能力业务零引用的情况下, 继续新增功能会进一步扩大“建而不用”的存量。
  解冻前提: M6 存量组件中至少一项从 `⚪ Built, Unused` 转为 `🟢 Completed` (即找到真实业务用例并接入), 且 M7-02 gRPC 完成第二业务模块验收。

### 2026-09-29 在途收尾与下一阶段

以下状态覆盖上方历史进度中的过时描述：

- gRPC 原生生命周期与自动配置已完成（`8bcbe63`），已移除 LogNet 依赖。系统用户、部门适配层已接入（`0c8ba36`），仍为默认关闭的实验功能，不能生产开启。
- gRPC 调用安全边界已交付：内部签名校验、调用方身份、租户上下文三道边界，经真实 TLS 网络调用验收。`hasPermi` 在 gRPC 通道内仍恒为 false，写操作未放开。
- 通知直接 TLS 配置及真实组件握手测试已补齐（`b984269`），TLS 1.2/1.3、缺少身份头拒绝、心跳、推送及连接清理通过。反向代理、可信网关和生产容器链路尚未验收，M7-03 不应整体关闭。
- **下一阶段优先完成 M7 验收缺口**：gRPC 方法级权限映射与数据范围过滤 → 第二业务模块客户端接入与网络集成测试 → 通知可信网关和容器 TLS 入口验证。
- 上述前置验收完成后，执行 M7-07 性能基准更新。**M7-04 CDC、M7-05 模板市场、M7-06 编辑器插件已冻结**（见上方“未来 M7+ 已冻结”）。当前不将设计或组件测试标记为生产交付。
- 质量基线：`mvn -o test` 410 项测试 0 失败（2026-09-29 clean 后实测, 以 surefire XML 为准）。
