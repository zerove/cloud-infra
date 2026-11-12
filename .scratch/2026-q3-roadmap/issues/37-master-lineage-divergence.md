# issue 37: master 与本地 lineage 分叉 —— 远端缺失 2026-10/11 加固波次, 且在生产形状库上启动失败

Status: open

## 一句话

仓库存在**两条互不包含的 master lineage**：`origin/master`（远端，762 commits，2026-11-12）
与本地 `master`（984 commits，2026-11-08）。远端缺少本地已有的加固内容（137 个文件，含 45 个门禁
脚本、3 个 Flyway 迁移、2 个安全/功能修复），**并且把远端 master 部署到"生产形状"的数据库上
会启动失败**（已运行时复现）。

## 现象（实测，2026-11-12）

### 1. 两条 lineage 互不包含

```bash
git rev-list --count master origin/master     # 984 / 762
git merge-base master origin/master            # 81e9bf0c (老提交, 非近 3 周)
git log --oneline origin/master..master | wc -l   # 数百条
```

证据分级：**L1 结构性**（git 层面可判定）。

### 2. 文件级差异（客观口径）

```bash
git ls-tree -r --name-only archive/local-lineage-2026-11-08 | sort > A
git ls-tree -r --name-only origin/master                | sort > B
comm -23 A B | wc -l   # 137  仅本地有
comm -13 A B | wc -l   # 128  仅远端有
```

仅本地 lineage 有的（按目录）：

| 数量 | 路径 | 含义 |
|---|---|---|
| 45 | `deploy/tests/` | 门禁脚本 + 其守护（含 `test_gate_wiring.py`、`quick_audit.sh`） |
| 8 + 4 + 2 | `cloud-infra-test-support/.../diagnostics` | 宿主负载诊断夹具 |
| 6 | `docs/analysis/` | 分析报告 |
| 3 | `system-module/.../db/migration/` | **V24 / V25 / V26 迁移文件** |
| 3 | `docs/audit/` | 审计文档 |
| 5 | `file-service/src/test/...` | 文件删除端点 E2E |
| 3 | `gateway/src/test/.../config` | 网关超时配置守护 |
| 4 | `system-module/.../domain/` | Monitoring/虚拟线程/存储统计视图 |

仅 origin/master 有的（128）：`cloud-infra-codegen/**`（含模板与测试）、
`cloud-infra-common/.../core/workflow/**`（编排引擎）、`deploy/grafana/dashboards/*.json`
（本地已改为 Jsonnet 源 `deploy/monitoring/grafana-dashboards/*.libsonnet`）、`manifests/base/**`。

> 即：**远端仍保留 2026-10-13 已按"完成 = 被业务使用"原则删除的零引用框架代码**（codegen 2464 行 +
> 编排引擎 540 行），本地已删。

### 3. 迁移集缺口 → 部署到真实库会启动失败（**L3 运行时复现**）

```bash
# 远端 master 的迁移集
git ls-tree -r --name-only origin/master -- '*/db/migration' | sort -V | tail -3
#   V23__notification_service_tables.sql
#   V27__add_tenant_id_to_audit_log_tables.sql      ← 缺 V24 / V25 / V26

# 本地 lineage 的迁移集
git ls-tree -r --name-only archive/local-lineage-2026-11-08 -- '*/db/migration' | sort -V | tail -3
#   V24__hot_path_indexes.sql
#   V25__add_error_code_permission_seed.sql
#   V26__user_search_trgm_index.sql
```

**部署库（真实环境）这三条已 applied**（原样记录）：

| version | description | checksum | installed_on |
|---|---|---|---|
| 24 | hot path indexes | 1454341787 | 2026-10-17 14:13:54 |
| 25 | add error code permission seed | -1087084318 | 2026-10-17 14:14:02 |
| 26 | user search trgm index | 972829989 | 2026-11-08 03:32:36 |

**复现**（把历史恢复成生产形状 → 重启 system 容器 → 镜像来自交付后的 master）：

```text
org.flywaydb.core.internal.command.DbValidate:
  Validate failed: Migrations have failed validation
  Detected applied migration not resolved locally: 24.
  Detected applied migration not resolved locally: 25.
  Detected applied migration not resolved locally: 26.
org.springframework.beans.factory.BeanCreationException:
  Error creating bean with name 'flywayInitializer' ...
  Caused by: org.flywaydb.core.api.exception.FlywayValidateException
→ 容器启动即失败（APPLICATION FAILED TO START）
```

> **注**：本次交付（PR #84）把新迁移定为 **V27** 而非 V24，正是为了避开这个冲突；
> 当时在本地的绕过手段是**删除生产库的 24/25/26 历史行**——那是**本地数据删除**，绝不能随交付上线。

### 4. 安全 / 功能修复在远端缺失

| 缺失项 | 本地 lineage | origin/master | 影响 |
|---|---|---|---|
| `PermissionService` 超管旁路 | `equalsIgnoreCase("SUPER_ADMIN")` + 守护测试 | 旧值 `"admin"`（与库中 `role_key='SUPER_ADMIN'` 大小写不匹配） | 远端**超管旁路从未生效**（fail-closed 的功能缺陷） |
| `HeaderAuthenticationFilter` 异步重派发 | 补齐 `SecurityContext` 传递 + 守护测试 | 旧实现 | 远端**大文件下载 100% 失败**（异步重派发丢失身份） |
| 45 个门禁脚本 + 接线守护 | 存在并接入 CI | 不存在 | 远端 CI **不执行**这些门禁（第 4 类假门禁的结构性形态） |

### 5. AGENTS.md 版本倒退

| | `origin/master` | 本地 lineage |
|---|---|---|
| AGENTS.md | 旧版（称 ZGC、称 4200 行零引用代码仍在） | 新版（G1GC 256m、称已删除、引用 `docs/lessons/debugging-log.md`） |
| `docs/lessons/debugging-log.md` | 不存在（5581 行） | 存在 |

> 该现象可作为 lineage 判定的**快速指纹**：checkout 不同分支时 AGENTS.md 内容会随之切换。

## 机制（为什么会出现两条 lineage）

远端历史经过一次**改写**（仓库内有 `chore(ops): 为"推送已重写历史"提供带前置检查的脚本`），
目的是清除泄露的 dev JWT 密钥。改写后：

- **PR 型提交被重放**（如 `replay(pr78): B3 notification scope + internal HMAC audit logs (#79)`）；
- **直接提交到 master 的那一波（2026-10-05 → 2026-11-08）未被重放** → 门禁脚本、调试日志、
  V24–V26 迁移、codegen 删除、2 个安全修复等一并从远端消失。

即：**改写的副作用是静默丢失了一整波内容**，且本地与远端从那以后再无交集。

## 影响面

1. **部署阻断**（最高）：任何已应用 V24–V26 的环境部署远端 master → system-module 启动失败。
2. **安全回退**：远端缺少 2 个修复（超管旁路失效 / 大文件下载失败）。
3. **门禁退化**：远端 CI 不再执行 45 个门禁脚本及其接线守护。
4. **框架回流**：远端仍带 codegen / 编排引擎等零引用代码，违反"完成 = 被业务使用"（AGENTS.md 硬规则）。

## 已采取的保护措施（本 issue 内）

- 建立 `archive/local-lineage-2026-11-08` 分支保存本地 lineage（1297 个文件全部保全）；
- 按"**仅当分支 tip 在保留 ref 中仍可达才删除**"的规则清理了已合并分支（24 个本地 + 10 个远端），
  保留了所有在途 / 未合 / 备份分支；
- **未**对 master 做任何 reset / force-push（属破坏性操作，需人工决策）。

## 修复方案（待决策）

| 方案 | 做法 | 代价 / 风险 |
|---|---|---|
| **A. 树收敛（建议）** | 以本地 lineage 为准，把 137 个缺失文件补回远端，并删除远端残留的 codegen / 编排引擎（128 文件中的零引用部分） | 变更面大（~265 文件），但**一次做完**；需 CI + E2E 全绿后合入 |
| B. 只补迁移 | 仅补 V24/V25/V26 三个迁移文件，先解除部署阻断 | 最小、最快；其余缺口（门禁/安全修复/AGENTS.md）继续存在 |
| C. 重建历史 | 用仓库自带的"重写历史"脚本，把本地 lineage 重新推到 master | 触及全仓历史，**风险最高**；且需再次清理密钥泄露 |

**建议顺序**：先 B（解除部署阻断，且成本最低）→ 再 A（一次收敛）→ 决策是否需要 C。

## 复跑证据

```bash
# 1. 两条 lineage 的提交数与 merge-base
git rev-list --count master origin/master; git merge-base master origin/master

# 2. 文件级差异
git ls-tree -r --name-only archive/local-lineage-2026-11-08 | sort > /tmp/A
git ls-tree -r --name-only origin/master                | sort > /tmp/B
comm -23 /tmp/A /tmp/B | wc -l   # 137
comm -13 /tmp/A /tmp/B | wc -l   # 128

# 3. 迁移集缺口
git ls-tree -r --name-only origin/master -- '*/db/migration' | sort -V | tail -3

# 4. 部署库形状
podman exec -i cloud-postgres psql -U postgres -d cloud_infra \
  -c "SELECT version, description, checksum, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"

# 5. 安全修复差异
git diff master origin/master -- cloud-infra-framework/framework-security/src/main/java/com/cloudinfra/framework/security/service/PermissionService.java
```

## 关联

- issue #36（跨租户审计泄漏）—— 本 issue **不影响**其交付内容：#79–#85 的改动在远端 master 完整存在；
- PR #84（deep defense）、#85（交付归档 + ADR-011）；
- AGENTS.md《第三/第四/第五课》：能力存在 ≠ 能力在运行；门禁脚本自己从未被调用。
