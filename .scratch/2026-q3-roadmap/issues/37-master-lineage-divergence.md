# issue 37: master 与本地 lineage 分叉 —— 远端缺失 2026-10/11 加固波次（部署阻断已解除）

Status: open（方案 B 完成；方案 A 持续推进中：安全修复、核心 gate 恢复/接线、零使用 codegen 清理、适用 gate 回补、docs/lessons+analysis+audit 资产对齐及 Prometheus 告警加载修复/守护恢复已完成；剩余门禁覆盖与其余文档/资产/配置收敛待推进）

## 一句话

仓库存在**两条互不包含的 master lineage**：`origin/master`（远端）与本地 `master`（984 commits，2026-11-08，已由 `archive/local-lineage-2026-11-08` 保全）。远端历史在密钥清理改写时**未重放 2026-10-05 → 2026-11-08 的直接提交波次**，导致大量加固内容缺失；其中**迁移集部署阻断已解除**，关键安全/功能修复、核心 gate 恢复与接线、零使用 `cloud-infra-codegen` 清理、首批适用 gate 回补、`docs/lessons`/`docs/analysis`/`docs/audit` 资产对齐及 Prometheus 告警规则加载修复与守护恢复已完成，但门禁脚本覆盖与其余文档/资产/部署配置仍未与 lineage 收敛。

## 进展

| 时间 | 动作 | 结果 |
|---|---|---|
| 2026-11-12 | 落盘 issue、L3 复现 Flyway 启动失败（PR #86, `980e8cd9`） | 证据归档 |
| 2026-11-12 | **方案 B 完成**（PR #87, `20c698ff`） | 恢复 `V24/V25/V26`（校验和与已 applied 历史一致）+ `ErrorCodeController` 类级鉴权与守护；CI/E2E 全绿；**生产形状库部署阻断解除** |
| 2026-11-12 | 状态刷新（PR #88, `fd384896`） | 更新剩余缺口口径 |
| 2026-11-13 | **方案 A 安全切片完成**（PR #89, `448b565b`） | 补回 `PermissionService` 超管旁路修复 + `HeaderAuthenticationFilter` 异步重派发身份重建，含 `PermissionServiceSuperAdminRoleKeyTest` / `StreamingResponseAsyncDispatchTest`；CI/E2E 全绿 |
| 2026-11-13 | 状态刷新（PR #90, `43155942`） | 同步 #89 后差异/缺口口径 |
| 2026-11-13 | **方案 A gate 恢复切片完成**（PR #91, `f2a027b0`） | 恢复并适配 `deploy/tests/quick_audit.sh`（fail-count 汇总、`InternalNotificationController` 豁免、`--self-test` 负向对照、仅扫 `src/main` controller、去除对 `rg` 依赖）；本地与 CI 通过 |
| 2026-11-13 | **方案 A gate 接线切片完成**（PR #92, `13fa999e`） | 新增 `deploy/tests/test_gate_wiring.py`；e2e workflow 增加 Maven cache 前置、聚合执行 `deploy/tests/test_*`、`test_gate_wiring.py` 与 `quick_audit.sh`；修复 `verify-e2e.sh` 2 处裸 `podman inspect` 超时缺口；将 `check_sw_oap_config.sh` 作为真实调用点接入 `verify-e2e.sh`（因 `origin/master` 已知 SW OAP/search 共用 ES 9.x 配置，当前为非阻断告警）；CI/E2E + native 全绿 |
| 2026-11-13 | 状态刷新（PR #93, `7894fd92`） | 记录 #90/#91/#92 后的 gate 恢复/接线进展并刷新缺口口径（基线 `origin/master@13fa999e`） |
| 2026-11-13 | **方案 A 零使用清理切片完成**（PR #94, `d5b67de3`） | 删除 `cloud-infra-codegen/**`、`docs/codegen/`、`.github/workflows/codegen-template-regression.yml`；移除根 pom 模块接线并对齐 CI/PMD 注释与 README/ARCHITECTURE/CONTEXT/AGENTS/ROADMAP；CI/native 全绿，E2E 首次为环境 OOM（`cloud-search`/`cloud-notification` `exit=137`）后 rerun 通过并合并 |
| 2026-11-13 | **方案 A 适用 gate 回补切片完成**（PR #95, `98fe821b`） | 恢复 `deploy/tests/test_attr_restore_pattern.py`（对当前树适用、随 e2e 聚合 `deploy/tests/test_*` 与 `test_gate_wiring.py` 守护）；曾尝试恢复 `test_start_period_covers_startup.py` 但因依赖当前树缺失的 `check_start_period_covers_startup.py` 且不适配 `python3` 聚合执行而移除；CI/E2E/native 全绿 |
| 2026-11-13 | 状态刷新（PR #96, `148f5175`） | 同步 #93/#94/#95 后缺口口径（基线 `origin/master@98fe821b`） |
| 2026-11-13 | **方案 A 文档/资产对齐切片完成**（PR #97, `35328a4d`） | 恢复 `docs/lessons/debugging-log.md`（5581 行）；CI/E2E/native 全绿；`docs/lessons` 缺口收敛 |
| 2026-11-13 | 状态刷新（PR #98, `1f54724e`） | 同步 #96/#97 后缺口口径（基线 `origin/master@35328a4d`） |
| 2026-11-13 | **方案 A 文档/资产对齐切片完成**（PR #99, `14552c59`） | 恢复 `docs/analysis/` 6 份与 `docs/audit/` 3 份 lineage 资产；CI/E2E/native 全绿；对应路径缺口收敛 |
| 2026-11-13 | 状态刷新（PR #100, `0cfde30a`） | 同步 #98/#99 后缺口口径（基线 `origin/master@14552c59`） |
| 2026-11-13 | **方案 A 监控告警修复与 gate 恢复切片完成**（PR #101, `490b8eaa`） | 将 `deploy/monitoring/prometheus-rules/gateway-alerts` 从 PrometheusRule CRD 转为原生规则文件（`.yml`，2 groups / 20 rules），恢复 `deploy/tests/test_prometheus_rule_files.py`；CI/E2E/native 全绿 |

## 当前状态（2026-11-13，基于 `origin/master@490b8eaa` vs `archive/local-lineage-2026-11-08`）

### 1. 两条 lineage 仍互不包含

```bash
git rev-list --count master origin/master   # 本地 lineage: 984 / 远端持续演进
git merge-base master origin/master         # 81e9bf0c
```

### 2. 文件级差异（客观口径，已刷新）

```bash
git ls-tree -r --name-only archive/local-lineage-2026-11-08 | sort > /tmp/A
git ls-tree -r --name-only origin/master                        | sort > /tmp/B
comm -23 /tmp/A /tmp/B | wc -l   # 119  仅本地 lineage 有
comm -13 /tmp/A /tmp/B | wc -l   # 96   仅 origin/master 有
```

**仍仅本地 lineage 有（关键项）**：
- `deploy/tests/` 大量门禁/守护脚本仍缺失（本地 66 vs 远端 28；远端缺 **41 个**，含 `check_*`/`test_*` 守护与判定脚本；`quick_audit.sh`、`test_gate_wiring.py`、`test_attr_restore_pattern.py`、`test_prometheus_rule_files.py` 已存在于 `origin/master`）
- `cloud-infra-test-support/.../diagnostics` 宿主负载诊断夹具
- （`docs/analysis/`、`docs/audit/` 及 `docs/lessons/debugging-log.md` 已于 #97/#99 对齐恢复）
- `file-service` / `gateway` / `system-module` 等剩余加固测试与 domain 视图

**仍仅 origin/master 有（关键项）**：
- 3 个 `deploy/tests` 脚本为 `origin/master` 独有（`prepare_evidence_fixture.py`、`run_evidence_it.py`、`test_resilience4j_binder_present.py`），与 lineage 集合不一致
- `manifests/base/**`、部分历史 dashboard 文件等与本地 lineage 重构/删除不一致的残留
- （`cloud-infra-codegen/**`、`docs/codegen/`、`codegen-template-regression.yml` 已于 #94 移除，不再属于此类缺口）

### 3. 已解除 / 已修复

- ✅ **部署阻断（方案 B）**：`V24/V25/V26` 已恢复并与 `flyway_schema_history` 校验和一致；`ErrorCodeController` 与 V25 种子成对受 `@PreAuthorize` 保护。
- ✅ **超管旁路失效**：`PermissionService` 改为对 `SUPER_ADMIN`（取自 `V1__init_schema.sql`）大小写不敏感匹配；`PermissionServiceSuperAdminRoleKeyTest` 守护真值不漂移。
- ✅ **流式下载异步重派发丢失身份**：`HeaderAuthenticationFilter.shouldNotFilterAsyncDispatch()=false`，按请求头+HMAC 在 ASYNC 重派发重建身份；`StreamingResponseAsyncDispatchTest` 守护流式/对照/拒绝路径。
- ✅ **quick_audit gate 恢复与 CI 接线**：`quick_audit.sh` 已存在并具备 fail-count 汇总、豁免与 `--self-test` 负向对照，纳入 e2e workflow 执行。
- ✅ **gate 接线守护与聚合执行**：`test_gate_wiring.py` 已存在；e2e workflow 聚合执行当前 `deploy/tests/test_*` 并运行 `test_gate_wiring.py`、`quick_audit.sh`；`check_sw_oap_config.sh` 已在 `verify-e2e.sh` 有真实调用点（当前因已知 ES 共享/版本不匹配为非阻断告警，待 ES/config 收敛后应恢复阻断）。
- ✅ **零使用残留清理**：`cloud-infra-codegen/**`（32 文件）、`docs/codegen/` 与专用 workflow 已移除，根 pom/CI/PMD/核心文档已对齐（#94）。
- ✅ **Prometheus 告警规则可加载性修复与守护恢复**：`gateway-alerts` 已由 PrometheusRule CRD（`.yaml`）转为原生规则文件（`.yml`，2 groups / 20 rules），并恢复 `test_prometheus_rule_files.py`（与现有 `*.y*ml` glob 及 e2e 聚合兼容，#101）。

### 4. 仍未收敛（方案 A 后续）

| 缺口 | 现状 | 影响/风险 |
|---|---|---|
| 门禁脚本覆盖 | lineage 仍有 41 个 `deploy/tests` 脚本未回补（`quick_audit.sh`、`test_gate_wiring.py`、`test_attr_restore_pattern.py`、`test_prometheus_rule_files.py` 已恢复/存在）；多项 `test_*` 依赖缺失配套 `check_*` 或当前树不存在的文档/资产，需按适用性逐项恢复并补齐调用点/负向对照 | 回归风险仍高于 lineage；盲目拷贝易引入不适配/假门禁 |
| 文档/资产偏离 | `docs/analysis/`、`docs/audit/`、`docs/lessons/debugging-log.md` 已恢复；其余 lineage 文档/部分加固测试与 domain 视图仍缺失 | 判据与操作指引仍有分叉，排查/治理资产未完全收敛 |
| 部署态配置收敛 | `check_sw_oap_config` 对 `origin/master` 当前 SW OAP/search 共用 ES 9.x 配置仍报不匹配（已非阻断接线告警） | ES/配置未收敛前无法将该守护升为阻断 |
| 树级残留差异 | `manifests/base/**`、部分 dashboard/脚本/文档等与本地 lineage 重构或删除不一致 | 交付形态与运维资产未统一，后续收敛需分批审慎对齐 |

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
| **A. 树收敛** | 分批补回缺失加固、恢复门禁与接线、删除远端残留零引用代码，最终使树一致 | 🔄 进行中（#89 安全、#91/#92 gate 恢复与接线、#94 codegen 清理、#95 适用 gate 回补、#97/#99 docs/lessons+analysis+audit 资产对齐、#101 Prometheus 告警加载修复与守护恢复完成；剩余门禁覆盖、其余文档/资产与配置/树级残留待分批推进） |
| **C. 重建历史** | 重推本地 lineage | 风险最高，需再次清理密钥泄露；不建议优先 |

> 建议后续按**独立可验证切片**推进 A：按适用性恢复并接线缺失 `deploy/tests` 脚本（优先与当前树直接匹配、可配套 `check_*`/调用点/负向对照的项），文档/资产与部署配置/`manifests` 各自成 PR，均要求 CI/E2E 全绿与必要守护。`check_sw_oap_config` 应在 ES/config 收敛后恢复阻断；若出现真实平台级审计需求，再按需起 ADR-012（不放宽租户过滤）。

## 复跑证据

```bash
# 文件级差异（当前口径）
git ls-tree -r --name-only archive/local-lineage-2026-11-08 | sort > /tmp/A
git ls-tree -r --name-only origin/master                        | sort > /tmp/B
comm -23 /tmp/A /tmp/B | wc -l   # 119
comm -13 /tmp/A /tmp/B | wc -l   # 96

# deploy/tests 覆盖口径
git ls-tree -r --name-only origin/master -- deploy/tests | wc -l                                      # 28
git ls-tree -r --name-only archive/local-lineage-2026-11-08 -- deploy/tests | wc -l                  # 66
comm -23 <(git ls-tree -r --name-only archive/local-lineage-2026-11-08 -- deploy/tests | sort) \
         <(git ls-tree -r --name-only origin/master -- deploy/tests | sort) | wc -l                   # 41
comm -13 <(git ls-tree -r --name-only archive/local-lineage-2026-11-08 -- deploy/tests | sort) \
         <(git ls-tree -r --name-only origin/master -- deploy/tests | sort)                           # prepare_evidence_fixture.py / run_evidence_it.py / test_resilience4j_binder_present.py

# 已修复项/已恢复 gate 指纹
git show origin/master:cloud-infra-framework/framework-security/src/main/java/com/cloudinfra/framework/security/service/PermissionService.java | grep equalsIgnoreCase
git show origin/master:cloud-infra-framework/framework-security/src/main/java/com/cloudinfra/framework/security/filter/HeaderAuthenticationFilter.java | grep -n shouldNotFilterAsyncDispatch
git ls-tree -r --name-only origin/master -- deploy/tests/quick_audit.sh deploy/tests/test_gate_wiring.py deploy/tests/test_attr_restore_pattern.py deploy/tests/test_prometheus_rule_files.py
rg -n "Gate self-tests|test_gate_wiring|Quick audit|check_sw_oap_config" .github/workflows/e2e.yml verify-e2e.sh

# 零使用清理与资产缺口指纹
git ls-tree -r --name-only origin/master | rg '^cloud-infra-codegen/' | wc -l   # 0
git ls-tree -r --name-only origin/master -- docs/lessons | wc -l                # 1
git ls-tree -r --name-only origin/master -- docs/analysis | wc -l               # 6
git ls-tree -r --name-only origin/master -- docs/audit | wc -l                  # 3
```

## 关联

- issue #36（PR #79–#85，ADR-011）
- PR #86（落盘 + L3 复现）、#87（方案 B）、#88（状态刷新）、#89（方案 A 安全切片）、#90（状态刷新）、#91（quick_audit 恢复/修复）、#92（gate 接线与聚合守护）、#93（状态刷新）、#94（codegen 零使用清理）、#95（适用 gate 回补）、#96（状态刷新）、#97（docs/lessons 资产对齐）、#98（状态刷新）、#99（docs/analysis、docs/audit 资产对齐）、#100（状态刷新）、#101（Prometheus 告警加载修复与守护恢复）
