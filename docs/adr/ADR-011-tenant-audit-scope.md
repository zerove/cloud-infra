# ADR-011: 跨租户审计时间线数据隔离口径 — 采纳租户级审计（B）

## 状态

2026-11-12 已决定（issue #36 收口；深化防御 PR #84 随之交付并验证）

## 背景

issue #36（跨租户审计时间线泄漏）的完整技术链：

1. `AuditController.getTimeline(userId)` 仅校验 `@ss.hasPermi('system:audit:list')`，**未校验**
   被查询 userId 是否属于调用方租户；
2. 审计源表本身无租户维度：`sys_oper_log` / `sys_notification_log` 在 V14 中被列入
   “不迁移的表”清单，且 Mapper 均无 `@TenantFilter`；
3. system → notification 内部调用（`AuditAggregatorService` → `notificationApi.getInternalLogs`）
   走内部 HMAC 契约但**未透传租户上下文**（virtual thread ThreadLocal 不继承，需手动注入）。

消歧实验（PR #82）确认：租户 B 非超管审计员（仅 `system:audit:list`）**仍能读到 tenant A 的
审计记录** → 缺口成立，与角色/超管权限无关。

## 决策

**采纳口径 B：租户级审计（tenant-scoped audit）为最终交付口径。**

### 交付内容（三层纵深，均已合入 master）

| 层 | PR | 内容 | 状态 |
|---|---|---|---|
| 端点前置校验 | #83 | 审计时间线端点校验 userId 归属调用方租户；`StandardResponseAdvice` Result 双层包装修复 | ✅ 合入 `d37a111d` |
| 表层纵深防御 | #84 | Flyway V27 补 `tenant_id` + `@TenantFilter`；Feign 内部 `X-Tenant-Id` 透传；aggregator virtual-thread 租户注入 | ✅ 合入 `32688660` |
| 内部调用契约 | #79 | internal HMAC（`X-Internal-Call`/`Timestamp`/`Nonce`）trusted `/internal/**`，网关不暴露 | ✅ 合入 `65ab4111` |

### B 口径下行为契约

- 持有 `system:audit:list` 的账号**仅可查看本租户**的审计时间线；
- 跨租户查询 → 拒绝（HTTP 200 + body code 拒绝，或按契约返回空数据）；
- 审计链路 `system → notification` 内部调用自动携带租户上下文，经同一内部签名边界验签后注入。

### 回滚 / 例外路径（A 口径）

- **不**通过放宽租户过滤、注释 `@TenantFilter` 等手段“临时”开放跨租户；
- 未来若确需平台级审计能力（如运维排障），须另建 ADR（ADR-012+）并落地**独立受控入口**
  （如仅限平台运维角色 + 显式开关），不得绕过租户过滤。

## 后果

- 安全默认态收紧为“本租户可见 + 纵深过滤”；风险面从“端点缺失”转为“可控实现”。
- 业务上对 B3 交付的既有契约无破坏（evidence runner 支持
  `-Devidence.s4.tenantIsolation.expectCode=200` 断言契约可切换）。

## 关联

- issue #36: `.scratch/2026-q3-roadmap/issues/36-cross-tenant-audit-timeline-leak.md`
- 交付归档: `docs/releases/FINAL_DELIVERY-2026-11-12-cross-tenant-audit.md`
- deep defense: PR #84（master `32688660`）
