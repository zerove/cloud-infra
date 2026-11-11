# issue 36: 跨租户审计读取未被隔离 —— `/system/audit/timeline` 可读他租户数据

Status: open

## 现象（真实运行时证据，2026-11-12）

在 merged master（`65ab4111`，PR #79 合并后）构建的本地真实容器栈上：

| 主体 | 请求 | 结果 |
| --- | --- | --- |
| tenant A（`admin`，JWT claim `tenantId=default`） | `GET /system/audit/timeline?userId=1` | 200，`source: NOTIFICATION` 记录 ✓ |
| tenant B（`evidence_b`，JWT claim `tenantId=tenant-b`） | `GET /system/audit/timeline?userId=1`（A 的用户 id） | **200 + 拿到 A 的记录**（`userId:1, action:发送通知 [email], resourceId:3, source:NOTIFICATION`）✗ |

复现命令（本地栈，见 PR #81 的 runner）：

```bash
python3 deploy/tests/run_evidence_it.py --scope s4 \
  --tenant-b-token "$TENANT_B_TOKEN" --tenant-a-user-id 1 \
  --require-notification-source          # 不给 --tenant-isolation-expect-code
```

→ `Tests run: 4, Failures: 1`，失败断言：

```
[tenant B must not read tenant A timeline (http=200 bodyCode=200 body={"code":200,"data":[
 {"timestamp":"...","userId":"1","action":"发送通知 [email]","resourceId":"3",
  "status":"SUCCESS","details":null,"source":"NOTIFICATION"}], ...})]
```

## 机制（为什么不是"探针读错了"）

1. `AuditController.getTimeline(userId)` 只校验 `@ss.hasPermi('system:audit:list')`，
   **不校验"被查询的 userId 是否属于调用方租户"**。
2. `sys_notification_log` / `sys_oper_log` **没有 `tenant_id` 列** ——
   `V14__add_tenant_id_columns.sql` 的"不迁移的表"清单显式包含这两张表。
3. system → notification 的内部调用（`AuditAggregatorService` → `notificationApi.getInternalLogs(userId)`）
   走内部 HMAC 契约，**不带租户上下文**（Feign 未透传 `X-Tenant-Id`），
   于是 notification 侧 `TenantContext` 为空 → `TenantSqlInterceptor` 不追加租户条件。

三者叠加：审计时间线对任何持有该权限的调用方都返回**目标用户的全部审计记录**。

## 待定的口径（产品决策，不是实现细节）

| 口径 | 含义 | 需要的改动 |
| --- | --- | --- |
| A. 平台级审计 | 超级管理员可跨租户查看（当前行为） | 明确写进文档/ADR，并用 `-Devidence.s4.tenantIsolation.expectCode=200` 固化契约 |
| B. 租户级审计 | 只能看本租户 | 端点校验 `userId` 归属租户；并给 `sys_notification_log` / `sys_oper_log` 补 `tenant_id`（迁移 + 回填 + 索引） |

**本 issue 不擅自改语义**（AGENTS.md 框架能力准入原则：提出来源必须是业务）。只保证：
证据可复跑、断言可切换、事实已留痕。

## 尚未排除的替代解释

本次 tenant B 被赋予与 A **完全相同的超级管理员角色**（差异仅租户），
因此"超管跨租户审计是设计意图"与"缺少租户校验"两种解释**无法区分**。
要证伪需要一个**非超管但持有 `system:audit:list`** 的租户 B 用户：
若它也能读到 A 的数据，则口径 B 下的缺口被证实（缺口与是否超管无关）。

## 证据工具（本 issue 产出，随 PR #81 引入）

- `NotificationSystemB3EvidenceIT.system_audit_timeline_tenantIsolation_when_configured`
  - 默认：断言**严格隔离**（被拒绝或查不到 + 无载荷）→ 当前**失败**（缺陷证据）
  - `-Devidence.s4.tenantIsolation.expectCode=200`：固化当前契约 → 通过
- `deploy/tests/run_evidence_it.py`：一键复跑（登录 → 注入参数 → failsafe）

## Comments

- 2026-11-12：随 PR #81（`feat/evidence-it-s4-s5`）建立。S4 其余 3 项、S5 全部 3 项在真实栈上通过。
