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
- [ ] 🔴 **Env Endpoint Sanitization**: `/management/env` 脱敏与暴露面收敛（P0）。→ *See `docs/OPTIMIZATION_PLAN_2026_08.md#S1`*

### 2. Reliability & Event-Driven Architecture
**Goal:** Improve system resilience and decouple service interactions.
- [x] 🟢 **TxMessage Retry Refactor**: Transition `TxMessageRetryTask` to use `StreamBridge` for asynchronous dispatch. → *See `plans/2026-05-08-tx-message-retry-refactor-plan.md`*
- [x] 🟢 **Dead Letter Queue (DLQ) Implementation**: Standardize error handling for failed stream messages.
- [x] 🟢 **Distributed Tracing Integration**: Full end-to-end trace verification for cross-module calls.
- [ ] 🔴 **Outbox 并发派发行锁**：多实例重复投递防护（FOR UPDATE SKIP LOCKED）。→ *OPTIMIZATION_PLAN R1*
- [ ] 🔴 **Outbox 原子性修复**：afterCommit 写库失败丢事件问题。→ *OPTIMIZATION_PLAN R2*
- [ ] 🔴 **集成测试从 0 到 1**：test-support 底座已就绪，各模块主链路 IT 补齐。→ *OPTIMIZATION_PLAN R3*

### 3. Framework Core & Developer Experience
**Goal:** Reduce friction for developers adding new services.
- [x] 🟢 **High-Level Context**: Establish `CONTEXT.md` for project onboarding.
- [x] 🟢 **Ubiquitous Language**: Define a canonical glossary in `UBIQUITOUS_LANGUAGE.md`.
- [x] 🟢 **Project Roadmap**: Create this master tracking document.
- [x] 🟢 **Developer Guide**: Create a "How-to" guide for implementing new modules using the framework.
- [ ] 🟡 **文档版本校准**: README/CONTEXT 与 pom 实际版本漂移修正（MyBatis Plus 3.5.17、jjwt 0.13.0、Redisson 4.7.0）。

### 4. Security Hardening (New, 2026-08)
**Goal:** Close identity/cache/credential gaps found in the 2026-08 audit.
- [ ] 🔴 **权限缓存失效链路**：用户/角色变更联动 clearPermissionCache。→ *OPTIMIZATION_PLAN S2*
- [ ] 🔴 **令牌吊销机制**：用户禁用/删除后存量 JWT 快速失效。→ *OPTIMIZATION_PLAN S4*
- [ ] 🔴 **MQ 凭据治理**：RabbitMQ guest/guest 改环境变量注入。→ *OPTIMIZATION_PLAN S3*
- [x] 🟢 **GraalVM Native 网关与认证服务交付**：网关 1.37s / auth 11.6s 启动，健康验证通过（提交链 f1e3e7a…07740fd）。

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
- [ ] Outbox 派发并发安全与原子性（R1/R2）。
- [ ] 集成测试覆盖主链路（R3）。

### Milestone 3: Scale & Optimization
*Focus: Performance tuning, advanced observability, and developer self-service.*
- [x] Developer Guide published (`docs/DEVELOPER_GUIDE.md`)。
- [ ] Native Image 性能基线建立（benchmark.sh --native）。
- [ ] JVM 启动时间剖析（当前 68.8s，目标 <30s）与 p99 延迟优化（当前 5.2s）。
- [ ] Advanced alerting rules configured (Prometheus)。

### Milestone 4: Security & Hygiene (Current, 2026-08)
*Focus: Audit remediation from OPTIMIZATION_PLAN P0/P3.*
- [x] 仓库卫生：class 编译产物出库、根目录日志清理、gitignore 加固。
- [ ] S1-S4 安全缺陷修复（env 脱敏 / 缓存失效 / MQ 凭据 / 令牌吊销）。
- [ ] 文档版本漂移一次性校正。
- [ ] E2E 测试真实化（登录链路断言替换 mock token）。
