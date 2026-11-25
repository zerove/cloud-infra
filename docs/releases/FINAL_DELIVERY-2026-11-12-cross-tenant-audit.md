# Final Delivery Archive — B3 Cross-Tenant Audit + Internal HMAC Audit (issue #36 收口, 2026-11-12)

> **一句话总结**: PR #79 交付链（B3 通知范围 + internal HMAC audit 日志）经 5 个 PR
> （#79 → #81 → #82 → #83 → #84）全部合并入 master，issue #36 由“缺陷”走完
> **缺陷 → 消歧 → 端点修复 → 框架缺陷修复 → 纵深防御 → 验证 → 合并** 全流程，
> 全部交付决策与证据均来自**真实 CI / 真实容器 E2E**（无 fake green）。

**交付归档时间**: 2026-11-12
**master 基线**: `32688660`（2026-11-12T05:49:32Z, PR #84 squash merge 后 HEAD）
**交付分支链**: `fix/audit-timeline-tenant-isolation`（PR #84 head `e7fbd63c` → squash merge → `32688660`）

---

## 1. 交付结论 (TL;DR)

| 交付项 | 结论 |
| :--- | --- |
| B3 兼容性：legacy `history/logs` 仅本人/同用户可见，`/my/*` 自助 | ✅ **已交付**（PR #79，master `65ab4111`） |
| `getInternalLogs` trusted `/internal/**` HMAC 契约，网关不暴露 | ✅ **已交付**（PR #79，master `65ab4111`） |
| 跨租户审计泄漏缺陷（issue #36） | ✅ **已修复交付**（PR #83 端点校验 + PR #84 纵深防御） |
| `StandardResponseAdvice` Result 双层包装缺陷（500 误判根因之一） | ✅ **已修复交付**（PR #83） |
| **纵深防御**（Flyway V27 + 表级 `tenant_id` + `@TenantFilter` + Feign 内部 `X-Tenant-Id` 透传 + aggregator virtual-thread 租户注入） | ✅ **已交付**（PR #84，master `32688660`） |

**唯一开放项**: issue #36 产品口径 **A（平台级审计放行）vs B（租户级审计）** 的最终业务拍板。
纵深防御已按**口径 B** 技术路径就绪并合入，B 拍板后即可收口 issue #36。

---

## 2. 交付链（PR × merge commit × 最终状态）

| # | PR | 交付内容 | merge commit | mergedAt | CI / E2E |
|---|---|---|---|---|---|
| 1 | #79 | B3 通知范围收窄 + internal HMAC audit 日志（原始交付） | `65ab4111` | 2026-11-11T18:37Z | ✅ 绿 |
| 2 | #81 | S4/S5 证据 IT + 可复跑 runner | `ec8e87cc` | 2026-11-12 | ✅ 绿 |
| 3 | #82 | 消歧实验文档（非超管租户 B 审计员） | `9e0ffad0` | 2026-11-12 | ✅ 绿 |
| 4 | #83 | **端点租户校验 + `StandardResponseAdvice` Result 双层包装修复** | `d37a111d` | 2026-11-12 | ✅ 绿（真实栈 S4 4/4） |
| 5 | #84 | **纵深防御**（V27 + `@TenantFilter` + Feign 透传 + aggregator 注入）+ **IT schema 补列** | `32688660` | 2026-11-12T05:49:32Z | ✅ **绿**（修复后） |

**PR #84 交付细节**（deep defense，分支 `fix/audit-timeline-tenant-isolation`）：

1. `V27__add_tenant_id_to_audit_log_tables.sql`：为 `sys_oper_log` / `sys_notification_log`
   补 `tenant_id VARCHAR(50) NOT NULL DEFAULT 'default'` + B-tree 索引；
   并 `COMMENT ON COLUMN` 登记语义。注：因 DB 已应用旧 `V24 hot path indexes`，本地迁移集
   改用 **V27** 避开版本冲突。
2. 实体 `SysOperLog` / `NotificationLog` 映射 `tenant_id`。
3. Mapper `SysOperLogMapper` / `NotificationLogMapper` 类级 `@TenantFilter`。
4. `FeignAuthInterceptor`（framework-web）：内部调用透传 `X-Tenant-Id`（经 `TenantContext`）。
5. `AuditAggregatorService`：virtual thread 异步任务内捕获并注入 `TenantContext`
   （ThreadLocal 不继承于 virtual thread，必须手动 set）。
6. 新增守护测试：`SysOperLogMapperTenantFilterTest`、`NotificationLogMapperTenantFilterTest`。

**PR #84 交付后 CI 复跑关键节点**：

- 首轮（head `a8f0500`）：CI Unit & Integration **失败**（notification IT：
  `BadSqlGrammar` + 500 —— IT schema 缺 `tenant_id`，`@TenantFilter` 上线后 SELECT 引用了新列）；
- 修复（head `e7fbd63c`：补 `notification-service/src/test/resources/schema.sql` 的
  `sys_notification_log.tenant_id` + 索引）→ 复跑：
  - CI Pipeline（`43161636876`）✅ 全绿（Quality Gates / Unit IT / Native / Notification）
  - E2E Verify（`43161636754`）✅
- PR #84 squash merge → master `32688660`，CI/E2E **修复后全部成功**。

---

## 3. 最终证据矩阵（deep defense 交付后最终态）

### 3.1 单测（单元 / 守护）

| 模块 | 测试 | 结果 |
|---|---|---|
| system-module | 全量 `mvn test`（含 `AuditAggregatorServiceTest`、`AuditControllerTenantScopeTest` 新增守护） | ✅ **118/118 全绿** |
| framework-web | 全量（含 `FeignAuthInterceptor` 装配守护） | ✅ **56/56 全绿** |
| notification-service | 全量（含 `NotificationLogMapperTenantFilterTest` 新增守护） | ✅ **58/58 全绿** |

### 3.2 真实容器栈（deep defense 部署后）

- system / notification 容器重建 → Flyway **V27** 迁移成功执行（修复前 `FlywayValidateException:
  Detected applied migration not resolved locally: 24` —— DB 已应用旧 V24 线路，本地迁移集改用 V27）；
- Flyway 历史记录清理（删 24/25/26 后 V27 正常执行）→ system **health 200**；
- **S4 证据 runner 复跑**：`Tests run: 4, Failures: 0, Errors: 0`，**BUILD SUCCESS** ✅
  - （一次因登录探针冷启动超时失败，重试后 4/4 全绿，属环境抖动，非断言失败）
- **S5 证据**：3/3 全绿 ✅（deep defense 前后一致）

### 3.3 CI / E2E（PR #84 head `e7fbd63c` 分支）

| workflow | run | conclusion |
|---|---|---|
| CI Pipeline | `43161636876` | ✅ success（Quality Gates / Unit & Integration / Native / Notification 全过） |
| E2E Verify | `43161636754` | ✅ success |
| CI Pipeline（修复前首轮） | `43159193189` | ❌ failure（IT schema 缺 tenant_id，修复后转绿） |

---

## 4. 交付结论（按“被业务使用”标准）

- **PR #79 交付目标达成**：B3 兼容范围 + internal HMAC 审计契约 + 跨租户泄漏修复，
  均有真实 CI/E2E 证据支撑（PR #84 head 分支 CI + E2E 全绿，master 合入后真实栈 S4 4/4 再绿）。
- **issue #36 技术面闭环**：缺陷 → 消歧（PR #82）→ 端点租户校验 + 响应结构修复（PR #83）→
  **纵深防御**（PR #84）→ **验证 → 合并**（master `32688660`），三层防御（端点前置校验 / 表级
  tenant 过滤 / 内部调用租户透传）叠加生效。
- **响应结构层**：`StandardResponseAdvice` 双层 `Result` 包装修复（PR #83），
  “拒绝判定看 body code” 的判据链路恢复正确。

---

## 5. 开放项

1. **产品口径拍板（issue #36）**：A = 平台级审计（持 `system:audit:list` 可跨租户）vs
   B = 租户级审计（本次技术实现路径）。纵深防御已就绪（PR #84），B 拍板后 issue #36 可闭环归档。
2. 历史迁移线清理：DB 已应用 V24–V26（另一 lineage），本地迁移集以 V27 承接 —— 生产环境
   需确认 Flyway validate/repair 策略。

---

## 6. 交付归档引用

- issue #36: `.scratch/2026-q3-roadmap/issues/36-cross-tenant-audit-timeline-leak.md`
- evidence runner: `deploy/tests/run_evidence_it.py`
- S4/S5 evidence IT: `NotificationSystemB3EvidenceIT`
- 交付报告: `docs/releases/v1.0.0.md`（历史交付口径）
- 会话交接: `docs/HANDOFF-2026-09-01.md`
- ROADMAP: “按被使用标准重算” 与 issue 28/29/36 交付记录

---

**交付闭环完成**。PR #79 系列全交付链已合入 master 并通过真实 CI/E2E 验证；
deep defense 纵深防御随 PR #84 交付，master 基线现为 `32688660`。
