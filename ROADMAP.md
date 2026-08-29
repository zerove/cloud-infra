# Project Roadmap: cloud-infra

This document serves as the master tracking list for the `cloud-infra` project, aggregating tasks from various implementation plans and architectural goals.

> 2026-08-25 校准：里程碑状态与代码实况对齐；新增 Milestone 4（安全与可靠性强化），
> 详细分析见 `docs/OPTIMIZATION_PLAN_2026_08.md`。

## 🚦 Status Legend
- 🔴 **To Do**: Not yet started.
- 🟡 **In Progress**: Implementation underway.
- 🟢 **Completed**: Implemented and verified.
- ⚪ **Planned**: Future consideration, not yet detailed.

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

### 4. Security Hardening (New, 2026-08)
**Goal:** Close identity/cache/credential gaps found in the 2026-08 audit.
- [x] 🟢 **GraalVM Native 网关与认证服务交付**：网关 1.37s / auth 11.6s 启动，健康验证通过（提交链 f1e3e7a…07740fd）。
- [x] 🟢 **权限缓存失效链路**（S2 已完成, commit 39ac527 + 6c55331 角色删除补齐）。
- [x] 🟢 **令牌吊销机制**（S4 已完成, commit ae6249f 用户禁用/删除/改密后吊销存量会话, JWT 黑名单 Redis TTL）。
- [x] 🟢 **MQ 凭据治理**（S3 已完成, deploy/compose.yaml 用 ${RABBITMQ_USER:?} 强制占位符）。
- [x] 🟢 **插件系统安全模型**（commit 7ac18f7 ADR-010 实施, dev 启用, prod 禁用）。

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
