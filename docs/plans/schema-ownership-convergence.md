# Schema 所有权收敛计划

**状态**：✅ **已实施并验证**（2026-10-15）。见文末「实施结果」。

## 现状

三个服务连同一个库 `cloud_infra`，DDL 演进有两套机制：

| 机制 | 拥有者 | 覆盖 |
| :--- | :--- | :--- |
| Flyway (`db/migration/V1..V21`) | system-module | 全部 `sys_*` + `sys_notification` + `sys_notification_preference`；**仅 ALTER** `file_metadata` |
| `spring.sql.init.mode=always` | file-service | `file_metadata` / `file_permission` / `file_version` / `sys_storage_quota` |
| 同上 | notification-service | `sys_notification` / `_log` / `_preference` / `_schedule` / `_rule` / `_template` |

重叠表：`sys_notification`、`sys_notification_preference`（双方建表）、`file_metadata`（Flyway 仅 ALTER）。

> `sql.init` 不是疏忽：它是当年为修「运行时表缺失」加的急救措施，
> 见 `deploy/config/notification-service.yaml:47-53` 与 `file-service.yaml:68-74` 的注释。
> 直接删掉会让未迁移的部署退回「表不存在」。

## 已验证的事实（真实 PostgreSQL 16，非推断）

1. `V1..V21` 在**全新库**上全部干净应用（21/21，无报错）。
2. `V14` 对 `file_metadata` 的 ALTER 被
   `IF EXISTS (SELECT 1 FROM information_schema.tables ...)` 包裹
   （[V14:63-64](file:///home/zeron/dev/cloud-infra/cloud-infra-modules/system-module/src/main/resources/db/migration/V14__add_tenant_id_columns.sql#L63-L64)），
   **表不存在时跳过**。⇒ 事后新增一个 `CREATE TABLE file_metadata` 的迁移是安全的。
3. `V21` 成功删除 `outbox_event`；`V20` 的两个索引均已建立。
4. `file_metadata.tenant_id` 目前来自 `file-service/schema.sql`，不由 Flyway 提供。

> 早前我判断「收敛被 Flyway 迁移不可变性阻断」——**该判断是错的**，
> 原因是没有读 V14 的 `IF EXISTS` 包裹。修正后的结论是：收敛**可行**。

## 实施方案

### 步骤 1：把 Flyway 补成完整的所有者（新增迁移，不动历史文件）

- `V22__file_tables.sql`：建 `file_metadata`（**含 `tenant_id`**，因为 V14 在全新库上会跳过）、
  `file_permission`、`file_version`、`sys_storage_quota`，全部 `CREATE TABLE IF NOT EXISTS`。
- `V23__notification_tables.sql`：建 `sys_notification_log`、`sys_notification_schedule`、
  `sys_notification_rule`、`sys_notification_template`，同样 `IF NOT EXISTS`。

存量库上这两个迁移是空操作（新表已存在）；全新库上补齐缺口。两条路径都已由上述事实支撑。

### 步骤 2：摘掉 `sql.init`

删除 file-service / notification-service 的 `src/main/resources/schema.sql`
及 `deploy/config/*.yaml` 中的 `spring.sql.init` 块。

### 步骤 3（**这是唯一的真实风险，必须处理**）：启动顺序

步骤 2 之后，file/notification 的表**完全依赖 system-module 先完成迁移**。

- compose / ArgoCD 中三个服务并列启动，**没有依赖顺序保证**。
- 若 notification 先启动并访问表 → `relation does not exist`。

处理选项（择一）：
- **a.** compose/k8s 加 initContainer 或启动探针，system-module 就绪后再放行其余服务；
- **b.** file/notification 各自启用**独立 history 表**的 Flyway（`spring.flyway.table`），
  消除跨服务排序依赖，但三实例会争同一把迁移锁；
- **c.** 保留 `sql.init` 作为**空 schema** 的兜底（不删配置，只让 schema.sql 变空）——
  成本最低，但 `sql.init` 仍在，机制未真正收敛。

### 步骤 4：验证

- `deploy/tests/test_schema_ddl_alignment.py` 收敛后应从「2 张重复声明」变为
  「未发现重复声明的表」——**这是该守护的自然验收信号**。
- 全新库 + 存量库各跑一次全量 IT。

## 为什么现在不做

步骤 3 的启动顺序依赖无法在本环境验证（podman 受限、Testcontainers 起不来），
而步骤 2 一旦有误会让 file/notification 在运行时**起不来**。
这属于需要一次可运行时验证的发布，不适合夹在一次守护/清理改动里顺手做掉。

已加 `deploy/tests/test_schema_ddl_alignment.py` 兜底：
它比对双方**最终态列集合**（含后续 `ALTER`），一旦有人只改一侧就会报红——
即重复声明期间**不会静默漂移**。