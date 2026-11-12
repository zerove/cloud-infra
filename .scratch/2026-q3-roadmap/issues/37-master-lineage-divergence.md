# issue 37: master 与本地 lineage 分叉 —— 远端缺失 2026-10/11 加固波次（部署阻断已解除）

Status: open（方案 B 已完成，方案 A 待决策）

## 一句话

仓库存在**两条互不包含的 master lineage**：`origin/master`（远端）与本地 `master`（984 commits，2026-11-08，已由 `archive/local-lineage-2026-11-08` 保全）。远端历史在密钥清理改写时**未重放 2026-10-05 → 2026-11-08 的直接提交波次**，导致大量加固内容缺失、零引用框架代码回流；其中**迁移集缺口已由方案 B 修复，部署阻断解除**，但安全/门禁/树收敛缺口仍在。

## 进展

| 时间 | 动作 | 结果 |
|---|---|---|
| 2026-11-12 | 落盘 issue、L3 复现 Flyway 启动失败（PR #86, `980e8cd9`） | 证据归档 |
| 2026-11-12 | **方案 B 完成**（PR #87, `20c698ff`） | 恢复 `V24/V25/V26`（校验和与已 applied 历史一致）+ `ErrorCodeController` 类级 `@PreAuthorize(system:error-code:list)` + `ErrorCodeControllerPreAuthorizeTest`；CI/E2E 全绿；**生产形状库部署阻断解除** |

## 当前状态（2026-11-13，基于 `origin/master@20c698ff` vs `archive/local-lineage-2026-11-08`）

### 1. 两条 lineage 仍互不包含

```bash
git rev-list --count master origin/master   # 本地 lineage: 984 / 远端: 765+（持续演进）
git merge-base master origin/master         # 81e9bf0c（非近 3 周）
```

### 2. 文件级差异（客观口径，已刷新）

```bash
git ls-tree -r --name-only archive/local-lineage-2026-11-08 | sort > /tmp/A
git ls-tree -r --name-only origin/master                        | sort > /tmp/B
comm -23 /tmp/A /tmp/B | wc -l   # 134  仅本地 lineage 有
comm -13 /tmp/A /tmp/B | wc -l   # 130  仅 origin/master 有
```

> 较初始 137/128 的变化：方案 B 补回 3 个迁移并新增配套守护测试/控制器保护，部署相关缺口收敛；其余缺口仍在。

**仍仅本地 lineage 有（关键项）**：
- `deploy/tests/` 门禁脚本与守护（本地 66 vs 远端 24；远端缺 `quick_audit.sh`、`test_gate_wiring.py` 等 42 个）
- `cloud-infra-test-support/.../diagnostics` 宿主负载诊断夹具
- `docs/analysis/`、`docs/audit/`、`docs/lessons/debugging-log.md`
- `file-service` 删除端点相关测试、`gateway` 超时配置守护、`system-module` 监控/统计 domain 视图等

**仍仅 origin/master 有**：
- `cloud-infra-codegen/**`（含模板与测试）
- `manifests/base/**`、部分 `deploy/grafana/dashboards/*.json` 等（本地已改为 Jsonnet 源）
- 及其他与本地 lineage 删除/重构不一致的残留

### 3. 迁移集缺口 — ✅ 已解除（方案 B）

`origin/master` 现已包含 `V24__hot_path_indexes.sql` / `V25__add_error_code_permission_seed.sql` / `V26__user_search_trgm_index.sql`，CRC32 与生产库已 applied 记录一致；`ErrorCodeController` 已与 V25 种子成对受 `@PreAuthorize` 保护。**“部署到生产形状库即 Flyway validate 失败”阻断已解除。**

### 4. 仍未收敛的缺口（需方案 A）

| 缺口 | 本地 lineage | origin/master（当前） | 影响 |
|---|---|---|---|
| `PermissionService` 超管旁路 | `equalsIgnoreCase("SUPER_ADMIN")` + 守护 | 旧值 `"admin"`（与 `role_key='SUPER_ADMIN'` 大小写不匹配） | **超管旁路在远端从未生效** |
| `HeaderAuthenticationFilter` 异步重派发 | `SecurityContext` 传递 + 守护 | 旧实现 | **流式下载等异步重派发场景丢失身份** |
| 门禁脚本与接线守护 | 66 个，含接线守护/quick_audit | 24 个，缺关键守护 | 远端 CI 不执行大量门禁（假门禁结构性风险） |
| 零引用框架代码 | 已按“完成 = 被业务使用”删除 | `cloud-infra-codegen` 等仍在 | 违反准入原则，框架回流 |
| 文档/AGENTS/分析资产 | 新版 + debugging-log + audit 记录 | 旧版/缺失 | 知识与判据倒退 |

## 机制（根因，保持不变）

远端历史为清除泄露 dev JWT 密钥做过改写：**PR 型提交被重放，2026-10-05 → 2026-11-08 直接提交波次未被重放**，与远端此后演进再无交集。

## 保护措施

- `archive/local-lineage-2026-11-08` 保全本地 lineage（1297 文件）
- 按“**仅当分支 tip 在保留 ref 中仍可达才删除**”清理已合并分支；在途/未合/备份分支保留
- **未**对 master 做 reset / force-push

## 待决策方案

| 方案 | 做法 | 状态/风险 |
|---|---|---|
| **B. 只补迁移** | 恢复 V24–V26 + 成对守护，解除部署阻断 | ✅ **已完成**（PR #87） |
| **A. 树收敛（建议）** | 以本地 lineage 为准补回缺失加固、删除远端残留零引用代码（当前差异 ~264 文件级） | 变更面大，需分批/可审计推进并保证 CI+E2E 全绿 |
| **C. 重建历史** | 用仓库脚本将本地 lineage 重推 master | 风险最高，需再次清理密钥泄露；不建议优先 |

## 复跑证据

```bash
# 迁移集（应含 V24-V27）
git ls-tree -r --name-only origin/master -- '*/db/migration' | sort -V | tail -8

# 文件级差异（当前口径）
git ls-tree -r --name-only archive/local-lineage-2026-11-08 | sort > /tmp/A
git ls-tree -r --name-only origin/master                        | sort > /tmp/B
comm -23 /tmp/A /tmp/B | wc -l   # 134
comm -13 /tmp/A /tmp/B | wc -l   # 130

# 仍缺失的安全修复指纹
git show origin/master:cloud-infra-framework/framework-security/src/main/java/com/cloudinfra/framework/security/service/PermissionService.java | grep -n 'SUPER_ADMIN'
```

## 关联

- issue #36（PR #79–#85，ADR-011）
- PR #86（issue 落盘 + L3 复现）
- PR #87（方案 B：迁移恢复 + ErrorCode 守护）— 已合并 `20c698ff`
