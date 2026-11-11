# issue 36: 跨租户审计读取未被隔离 —— `/system/audit/timeline` 可读他租户数据

Status: open

## 现象（真实运行时证据，2026-11-12，merged master `65ab4111` 镜像 + 真实容器栈）

| 主体 | 请求 | 结果 |
| --- | --- | --- |
| tenant A（`admin`，JWT claim `tenantId=default`） | `GET /system/audit/timeline?userId=1` | 200，`source: NOTIFICATION` 记录 ✓ |
| tenant B（`evidence_b`，JWT claim `tenantId=tenant-b`，**超级管理员角色**） | `GET /system/audit/timeline?userId=1`（A 的用户 id） | **200 + 拿到 A 的记录** ✗ |
| tenant B（`evidence_auditor_b`，JWT claim `tenantId=tenant-b`，**只有 `system:audit:list` 一个权限，非超管**） | `GET /system/audit/timeline?userId=1` | **200 + 拿到 A 的记录** ✗ |

复现（本地栈，runner 见 PR #81）：

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

## 消歧实验（结论：与"是否超管"无关，是端点缺少租户校验）

第一轮证据里 tenant B 恰好是超管，于是有两种解释无法区分：

- (a) "平台级审计：超管可跨租户查看" —— 有意为之
- (b) "端点缺少租户校验" —— 缺口

造一个**非超管**的租户 B 用户（只给 `system:audit:list` 一个权限），结论：

```text
[审计员读 tenant A 时间线] http=200 code=200 记录数=1
  body: {"code":200,"data":[{"userId":"1","action":"发送通知 [email]",
         "resourceId":"3","status":"SUCCESS","source":"NOTIFICATION"}], ...}
结论: READABLE(缺口: 非超管审计员也能跨租户读)
```

⇒ **(b) 成立**。租户级审计员可以读另一租户用户的审计记录，
"平台级审计"的解释不适用于最小权限审计员。

夹具（本地，可复现）：

```sql
-- 租户 B 专属权限行 + 角色（sys_permission/sys_role 有 tenant_id）
INSERT INTO sys_permission (id,name,perms,path,method,tenant_id)
  VALUES (9001,'审计查询(tenant-b)','system:audit:list','/system/audit/timeline','GET','tenant-b');
INSERT INTO sys_role (id,role_name,role_key,role_order,status,data_scope,create_time,update_time,deleted,tenant_id)
  VALUES (9001,'租户B审计员','AUDITOR_B',9,1,'1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,'tenant-b');
INSERT INTO sys_role_permission (role_id,permission_id) VALUES (9001,9001);
-- 用户: 管理端 API 创建(密码被覆盖为 sys.user.initPassword=123456) → 再改租户 → 绑角色
UPDATE sys_user SET tenant_id='tenant-b' WHERE username='evidence_auditor_b';
INSERT INTO sys_user_role (user_id,role_id) SELECT user_id,9001 FROM sys_user WHERE username='evidence_auditor_b';
```

## 机制（为什么不是"探针读错了"）

1. `AuditController.getTimeline(userId)` 只校验 `@ss.hasPermi('system:audit:list')`，
   **不校验"被查询的 userId 是否属于调用方租户"**。
2. **底层表本身就没有租户维度**：
   - `sys_notification_log`：**没有 `tenant_id` 列**（`V14__add_tenant_id_columns.sql` 的
     "不迁移的表"清单显式包含它），且其 Mapper `NotificationLogMapper` **没有 `@TenantFilter`**；
   - `sys_oper_log`：同样无 `tenant_id` 列、同样无 `@TenantFilter`。
3. 仓库内**已加 `@TenantFilter` 的 Mapper 只有 9 个**（实测枚举）：
   `SysUserMapper` / `SysRoleMapper` / `SysPermissionMapper` / `SysMenuMapper` /
   `SysPostMapper` / `SysDeptMapper` / `NotificationMapper` /
   `UserNotificationPreferenceMapper` / `FileMetadataMapper`。
   审计所依赖的 `SysOperLogMapper`、`NotificationLogMapper` **不在其中**。
4. system → notification 的内部调用（`AuditAggregatorService` → `notificationApi.getInternalLogs(userId)`）
   走内部 HMAC 契约且**不带租户上下文**（Feign 未透传 `X-Tenant-Id`），
   于是 notification 侧 `TenantContext` 为空 → 拦截器无从加条件。

即：**"端点不校验"与"表无租户维度"两层同时缺失**，任一层补上都能堵住当前这条路径。

## 待定的口径（产品决策，不是实现细节）

| 口径 | 含义 | 需要的改动 |
| --- | --- | --- |
| A. 平台级审计 | 任何持 `system:audit:list` 的账号可跨租户查看（当前行为） | 写进文档/ADR，并用 `-Devidence.s4.tenantIsolation.expectCode=200` 固化契约 |
| B. 租户级审计 | 只能看本租户 | 端点校验 `userId` 归属调用方租户（`sys_user.tenant_id` 已租户化，可直接用）；并给 `sys_notification_log` / `sys_oper_log` 补 `tenant_id` + `@TenantFilter`（纵深防御）；内部 HMAC 调用补租户透传 |

**本 issue 不擅自改语义**（AGENTS.md 框架能力准入原则：提出来源必须是业务）。只保证：
证据可复跑、断言可切换、事实已留痕。

## 证据工具（随 PR #81 引入，已合并 master `ec8e87cc`）

- `NotificationSystemB3EvidenceIT.system_audit_timeline_tenantIsolation_when_configured`
  - 默认：断言**严格隔离**（被拒绝或查不到 + 无载荷）→ 当前**失败**（缺陷证据）
  - `-Devidence.s4.tenantIsolation.expectCode=200`：固化当前契约 → 通过
- `deploy/tests/run_evidence_it.py`：一键复跑（登录 → 注入参数 → failsafe）

## Comments

- 2026-11-12：随 PR #81（`feat/evidence-it-s4-s5`）建立。S4 其余 3 项、S5 全部 3 项在真实栈上通过。
- 2026-11-12：补做**消歧实验**（非超管的租户 B 审计员，仅 `system:audit:list`）：
  仍能读到 tenant A 的记录 ⇒ 排除"超管特权"解释，缺口与角色无关。
  同时把机制细化到"两层缺失"：端点不校验 userId 归属 + 审计依赖的两张表无租户维度。
