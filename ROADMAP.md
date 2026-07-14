# Project Roadmap: cloud-infra

This document serves as the master tracking list for the `cloud-infra` project, aggregating tasks from various implementation plans and architectural goals.

## 🚦 Status Legend
- 🔴 **To Do**: Not yet started.
- 🟡 **In Progress**: Implementation underway.
- 🟢 **Completed**: Implemented and verified.
- ⚪ **Planned**: Future consideration, not yet detailed.

---

## 🔭 Strategic Themes

### 1. Observability & Management (Standardization)
**Goal:** Ensure every service in the ecosystem is equally observable and manageable.
- [x] 🟢 **Actuator Engineering**: Standardize management port (9301) and path (`/management`) across all services. $\rightarrow$ *See `plans/2026-04-21-actuator-engineering.md`*
- [x] 🟢 **Metrics Aggregation**: Implement standardized custom metrics registration in `framework-web`. $\rightarrow$ *See `plans/2026-04-25-monitoring-implementation.md`*
- [x] 🟢 **Centralized Log Aggregation**: Integrate ELK/Loki across all modules.

### 2. Reliability & Event-Driven Architecture
**Goal:** Improve system resilience and decouple service interactions.
- [x] 🟢 **TxMessage Retry Refactor**: Transition `TxMessageRetryTask` to use `StreamBridge` for asynchronous dispatch. $\rightarrow$ *See `plans/2026-05-08-tx-message-retry-refactor-plan.md`*
- [x] 🟢 **Dead Letter Queue (DLQ) Implementation**: Standardize error handling for failed stream messages.
- [x] 🟢 **Distributed Tracing Integration**: Full end-to-end trace verification for cross-module calls.

### 3. Framework Core & Developer Experience
**Goal:** Reduce friction for developers adding new services.
- [x] 🟢 **High-Level Context**: Establish `CONTEXT.md` for project onboarding.
- [x] 🟢 **Ubiquitous Language**: Define a canonical glossary in `UBIQUITOUS_LANGUAGE.md`.
- [x] 🟢 **Project Roadmap**: Create this master tracking document.
- [x] 🟢 **Developer Guide**: Create a "How-to" guide for implementing new modules using the framework.

---

## 📅 Timeline & Milestones

### Milestone 1: Foundation & Alignment (Current)
*Focus: Documentation, Core Framework standardization, and basic observability.*
- [x] Project Context defined.
- [x] Ubiquitous Language established.
- [ ] Actuator standardization applied to all core services.
- [ ] Framework-web metrics implemented.

### Milestone 2: Reliability Hardening
*Focus: Message reliability, transaction consistency, and error recovery.*
- [ ] TxMessage retry logic refactored.
- [ ] Seata distributed transaction verification across modules.

### Milestone 3: Scale & Optimization
*Focus: Performance tuning, advanced observability, and developer self-service.*
- [ ] Developer Guide published.
- [ ] Advanced tracing and alerting configured.
