# issue 37: master 与本地 lineage 分叉 —— 远端缺失 2026-10/11 加固波次（部署阻断已解除）

Status: open（方案 B 完成；方案 A 已完成 1 个安全切片，剩余待推进）

## 一句话

仓库存在**两条互不包含的 master lineage**：`origin/master`（远端）与本地 `master`（984 commits，2026-11-08，已由 `archive/local-lineage-2026-11-08` 保全）。远端历史在密钥清理改写时**未重放 2026-10-05 → 2026-11-08 的直接提交波次**，导致大量加固内容缺失、零引用框架代码回流；其中**迁移集部署阻断已解除**，**2 项关键安全/功能修复已补回**，但门禁与树收敛缺口仍在。

## 进展

| 时间 | 动作 | 结果 |
|---|---|---|
| 2026-11-12 | 落盘 issue、L3 复现 Flyway 启动失败（PR #86, `980e8cd9`） | 证据归档 |
| 2026-11-12 | **方案 B 完成**（PR #87, `20c698ff`） | 恢复 `V24/V25/V26`（校验和与已 applied 历史一致）+ `ErrorCodeController` 类级鉴权与守护；CI/E2E 全绿；**生产形状库部署阻断解除** |
| 2026-11-12 | 状态刷新（PR #88, `fd384896`） | 更新剩余缺口口径 |
| 2026-11-13 | **方案 A 安全切片完成**（PR #89, `448b565b`） | 补回 `PermissionService` 超管旁路修复 + `HeaderAuthenticationFilter` 异步重派发身份重建，含 `PermissionServiceSuperAdminRoleKeyTest` / `StreamingResponseAsyncDispatchTest`；CI/E2E 全绿 |

## 当前状态（2026-11-13，基于 `origin/master@448b565b` vs `archive/local-lineage-2026-11-08`）

### 1. 两条 lineage 仍互不包含

```bash
git rev-list --count master origin/master   # 本地 lineage: 984 / 远端持续演进
git merge-base master origin/master         # 81e9bf0c
```

### 2. 文件级差异（客观口径，已刷新）

```bash
git ls-tree -r --name-only archive/local-lineage-2026-11-08 | sort > /tmp/A
git ls-tree -r --name-only origin/master                        | sort > /tmp/B
comm -23 /tmp/A /tmp/B | wc -l   # 132  仅本地 lineage 有
comm -13 /tmp/A /tmp/B | wc -l   # 130  仅 origin/master 有
```

**仍仅本地 lineage 有（关键项）**：
- `deploy/tests/` 门禁脚本与守护（本地 66 vs 远端 24；远端缺 `quick_audit.sh`、`test_gate_wiring.py` 等 **45 个**，且 CI 仅接线 6 个脚本）
- `cloud-infra-test-support/.../diagnostics` 宿主负载诊断夹具
- `docs/analysis/`、`docs/audit/`、`docs/lessons/debugging-log.md`
- `file-service` / `gateway` / `system-module` 等剩余加固测试与 domain 视图

**仍仅 origin/master 有**：
- `cloud-infra-codegen/**`（含模板与测试，零业务引用）
- `manifests/base/**`、部分历史 dashboard 文件等与本地 lineage 重构/删除不一致的残留

### 3. 已解除 / 已修复

- ✅ **部署阻断（方案 B）**：`V24/V25/V26` 已恢复并与 `flyway_schema_history` 校验和一致；`ErrorCodeController` 与 V25 种子成对受 `@PreAuthorize` 保护。
- ✅ **超管旁路失效**：`PermissionService` 改为对 `SUPER_ADMIN`（取自 `V1__init_schema.sql`）大小写不敏感匹配；`PermissionServiceSuperAdminRoleKeyTest` 守护真值不漂移。
- ✅ **流式下载异步重派发丢失身份**：`HeaderAuthenticationFilter.shouldNotFilterAsyncDispatch()=false`，按请求头+HMAC 在 ASYNC 重派发重建身份；`StreamingResponseAsyncDispatchTest` 守护流式/对照/拒绝路径。

### 4. 仍未收敛（方案 A 后续）

| 缺口 | 现状 | 影响/风险 |
|---|---|---|
| 门禁脚本与接线 | 远端 24 个脚本、CI 接线极少；缺 `quick_audit.sh`/`test_gate_wiring.py` 及 45 个守护/判定脚本 | 回归风险高，存在假门禁空间（AGENTS.md 第 4 类） |
| 零引用框架代码回流 | `cloud-infra-codegen` 等仍在 | 违反“完成 = 被业务使用”准入原则 |
| 文档/资产偏离 | AGENTS/调试战史/分析与审计记录等与本地 lineage 不一致 | 判据与操作指引倒退/分叉 |

## 机制（根因，保持不变）

远端历史为清除泄露 dev JWT 密钥做过改写：**PR 型提交被重放，2026-10-05 → 2026-11-08 直接提交波次未被重放**，与远端此后演进再无交集。

## 保护措施

- `archive/local-lineage-2026-11-08` 保全本地 lineage（1297 文件）
- 按“**仅当分支 tip 在保留 ref 中仍可达才删除**”清理已合并分支；在途/未合/备份分支保留
- **未**对 master 做 reset / force-push

## 待决策 / 下一步

| 方案 | 做法 | 状态 |
|---|---|---|
| **B. 只补迁移** | 恢复 V24–V26 + 成对守护，解除部署阻断 | ✅ 已完成（#87） |
| **A. 树收敛** | 分批补回缺失加固、恢复门禁与接线、删除远端残留零引用代码，最终使树一致 | 🔄 进行中（#89 完成安全切片；门禁/零引用清理/资产对齐待分批推进） |
| **C. 重建历史** | 重推本地 lineage | 风险最高，需再次清理密钥泄露；不建议优先 |

> 建议后续按**独立可验证切片**推进 A：门禁脚本+CI 接线恢复、零引用模块清理、文档资产对齐各自成 PR，均要求 CI/E2E 全绿与必要的负向对照/接线守护。

## 复跑证据

```bash
# 文件级差异（当前口径）
git ls-tree -r --name-only archive/local-lineage-2026-11-08 | sort > /tmp/A
git ls-tree -r --name-only origin/master                        | sort > /tmp/B
comm -23 /tmp/A /tmp/B | wc -l   # 132
comm -13 /tmp/A /tmp/B | wc -l   # 130

# 已修复项指纹
git show origin/master:cloud-infra-framework/framework-security/src/main/java/com/cloudinfra/framework/security/service/PermissionService.java | grep equalsIgnoreCase
git show origin/master:cloud-infra-framework/framework-security/src/main/java/com/cloudinfra/framework/security/filter/HeaderAuthenticationFilter.java | grep -n shouldNotFilterAsyncDispatch

# 仍缺失的关键门禁
git ls-tree -r --name-only origin/master -- deploy/tests/quick_audit.sh deploy/tests/test_gate_wiring.py
```

## 关联

- issue #36（PR #79–#85，ADR-011）
- PR #86（落盘 + L3 复现）、#87（方案 B）、#88（状态刷新）、#89（方案 A 安全切片）
