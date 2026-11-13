# issue 37: master 与本地 lineage 分叉 —— 远端缺失 2026-10/11 加固波次（部署阻断已解除）

Status: open（方案 B 完成；方案 A 持续推进中：安全修复、核心 gate 恢复/接线、jacoco 覆盖率 gate 落地、零使用 codegen 清理、适用 gate 回补、docs/lessons+analysis+audit 资产对齐、Prometheus 告警加载修复与守护恢复、内部调用验签单一真源收敛、部署模板密钥去公开化与 `up.sh` 随机化、门禁语义接线自检（当前树适配）、K8s 探针路径/镜像 tag/Dockerfile JVM flags/端口抢占/文档探针脚本/compose depends_on/OTel pipeline 引用/课次表格/native 分配实证等守护恢复与接线已完成；剩余门禁覆盖与其余文档/资产/配置收敛待推进）

## 一句话

仓库存在**两条互不包含的 master lineage**：`origin/master`（远端）与本地 `master`（984 commits，2026-11-08，已由 `archive/local-lineage-2026-11-08` 保全）。远端历史在密钥清理改写时**未重放 2026-10-05 → 2026-11-08 的直接提交波次**，导致大量加固内容缺失；其中**迁移集部署阻断已解除**，关键安全/功能修复、核心 gate 恢复与接线、零使用 `cloud-infra-codegen` 清理、适用 gate 回补、`docs/lessons`/`docs/analysis`/`docs/audit` 资产对齐及 Prometheus 告警规则加载修复与守护恢复、内部调用验签单一真源收敛、部署模板密钥去公开化与 `up.sh` 随机化、门禁语义接线自检（按当前 `verify-e2e.sh` 适配）、jacoco gate 落地，以及部分适用 `deploy/tests` 守护恢复（含 `verify-e2e` 端口抢占前置门禁接线）已完成，但 **`deploy/tests` 门禁脚本覆盖与其余文档/资产/部署配置仍未与 lineage 收敛**。

## 进展

| 时间 | 动作 | 结果 |
|---|---|---|
| 2026-11-12 | 落盘 issue、L3 复现 Flyway 启动失败（PR #86, `980e8cd9`） | 证据归档 |
| 2026-11-12 | **方案 B 完成**（PR #87, `20c698ff`） | 恢复 `V24/V25/V26`（校验和与已 applied 历史一致）+ `ErrorCodeController` 类级鉴权与守护；CI/E2E 全绿；**生产形状库部署阻断解除** |
| 2026-11-12 | 状态刷新（PR #88, `fd384896`） | 更新剩余缺口口径 |
| 2026-11-13 | **方案 A 安全切片完成**（PR #89, `448b565b`） | 补回 `PermissionService` 超管旁路修复 + `HeaderAuthenticationFilter` 异步重派发身份重建，含守护测试；CI/E2E 全绿 |
| 2026-11-13 | 状态刷新（PR #90, `43155942`） | 同步 #89 后差异/缺口口径 |
| 2026-11-13 | **方案 A gate 恢复切片完成**（PR #91, `f2a027b0`） | 恢复并适配 `deploy/tests/quick_audit.sh`（fail-count 汇总、豁免、`--self-test` 负向对照等）；本地与 CI 通过 |
| 2026-11-13 | **方案 A gate 接线切片完成**（PR #92, `13fa999e`） | 新增 `deploy/tests/test_gate_wiring.py`；e2e workflow 聚合执行 `deploy/tests/test_*`、接入 `quick_audit.sh`；修复 `verify-e2e.sh` 裸 `podman inspect` 超时缺口；`check_sw_oap_config.sh` 真实接线（当前已知偏差下非阻断告警）；CI/E2E/native 全绿 |
| 2026-11-13 | 状态刷新（PR #93, `7894fd92`） | 记录 #90/#91/#92 后 gate 恢复/接线进展并刷新缺口口径 |
| 2026-11-13 | **方案 A 零使用清理切片完成**（PR #94, `d5b67de3`） | 删除 `cloud-infra-codegen/**`、`docs/codegen/`、`codegen-template-regression.yml`；移除根 pom/CI/PMD/文档接线；CI/native 全绿，E2E 环境 OOM 后 rerun 通过 |
| 2026-11-13 | **方案 A 适用 gate 回补切片完成**（PR #95, `98fe821b`） | 恢复 `deploy/tests/test_attr_restore_pattern.py`（当前树适用）；CI/E2E/native 全绿 |
| 2026-11-13 | 状态刷新（PR #96, `148f5175`） | 同步 #93/#94/#95 后缺口口径 |
| 2026-11-13 | **方案 A 文档/资产对齐切片完成**（PR #97, `35328a4d`） | 恢复 `docs/lessons/debugging-log.md`；CI/E2E/native 全绿 |
| 2026-11-13 | 状态刷新（PR #98, `1f54724e`） | 同步 #96/#97 后缺口口径 |
| 2026-11-13 | **方案 A 文档/资产对齐切片完成**（PR #99, `14552c59`） | 恢复 `docs/analysis/` 6 份与 `docs/audit/` 3 份 lineage 资产；CI/E2E/native 全绿 |
| 2026-11-13 | 状态刷新（PR #100, `0cfde30a`） | 同步 #98/#99 后缺口口径 |
| 2026-11-13 | **方案 A 监控告警修复与 gate 恢复切片完成**（PR #101, `490b8eaa`） | `gateway-alerts` 转为 Prometheus 原生规则文件（`.yml`），恢复 `deploy/tests/test_prometheus_rule_files.py`；CI/E2E/native 全绿 |
| 2026-11-13 | 状态刷新（PR #102, `9f7f124d`） | 同步 #100/#101 后缺口口径 |
| 2026-11-13 | **方案 A 内部调用验签单一真源切片完成**（PR #103, `9db7bfa3`） | 远端移植 `InternalCallVerifier`（fail-closed 单一真源）+ 守护/单测与 5 站点委托迁移；恢复 `deploy/tests/test_internal_call_verifier_wiring.py`；CI/E2E/native 全绿 |
| 2026-11-13 | 状态刷新（PR #104, `ec2c94a4`） | 同步 #103 后缺口口径 |
| 2026-11-13 | **方案 A jacoco gate 恢复切片完成**（PR #105, `c660340d`） | 恢复 `deploy/tests/test_jacoco_phase_wiring.py` 并接线 jacoco 覆盖率执行（phases/argLine/阈值基线校准、ArchUnit 子集步 `-Djacoco.skip=true` 规避误判）；CI/E2E/native 全绿后合并 |
| 2026-11-13 | **方案 A 部署模板密钥去公开化与随机化切片完成**（PR #106, `065cbf4b`） | `.env.example` 去公开密钥并补齐 `NACOS_AUTH_TOKEN` 空键；`deploy/up.sh` 统一随机化/回填；恢复 `deploy/tests/test_env_example_secrets.py`；CI/E2E/native 全绿（E2E 首次环境 OOM 后 rerun） |
| 2026-11-13 | **方案 A 门禁语义接线自检恢复与适配切片完成**（PR #107, `58855f4e`） | 恢复 `deploy/tests/test_gate_semantics_selftest.py` 并按当前 `verify-e2e.sh` 适配（阻断：`check_commit_budget`；advisory：`check_sw_oap_config`）；CI/E2E/native 全绿 |
| 2026-11-13 | 状态刷新（PR #108, `9b738b14`） | 同步 #104/#106/#107 与当时 #105 状态 |
| 2026-11-13 | **方案 A 适用 gate 对齐切片完成**（PR #109, `75955368`） | `deploy/tests/test_k8s_probe_paths.py` 对齐当前树（移除已删 `manifests/` 扫描根并强制扫描根存在性）；`test_image_tag_expansion.py` 验证通过；CI/E2E/native 全绿 |
| 2026-11-13 | **方案 A Dockerfile JVM flags gate 恢复切片完成**（PR #110, `8f544632`） | 恢复 `deploy/tests/test_dockerfile_jvm_flags.py`；清理 7 个 Dockerfile/template 中失效 CDS 拷贝与 `-XX:SharedArchiveFile`；CI/E2E/native 全绿 |
| 2026-11-13 | **方案 A 端口抢占 gate 恢复与接线切片完成**（PR #111, `15c0c369`） | 恢复 `deploy/tests/check_port_conflicts.py`、`deploy/tests/test_port_conflicts.py`；在 `verify-e2e.sh` 接入前置 `check_port_conflicts`（`rc=1/2` 阻断）；CI/E2E/native 全绿 |
| 2026-11-13 | **方案 A 文档探针脚本 gate 恢复切片完成**（PR #112, `94a69f9f`） | 恢复 `deploy/tests/test_docs_probe_scripts.py`（自包含，24/24 通过）；CI/E2E/native 全绿 |

| 2026-11-13 | 状态刷新（PR #113, `f900450c`） | 同步 #105/#108-#112 后缺口口径（基线 `origin/master@94a69f9f`） |
| 2026-11-13 | **方案 A compose/OTel 配置 gate 恢复与接线切片完成**（PR #114, `4c263fa9`） | 恢复 `check_compose_dangling_deps.py`/`test_compose_dangling_deps.sh`、`check_otel_pipeline_refs.py`/`test_otel_pipeline_refs.sh` 并在 `verify-e2e.sh` 接入前置阻断门禁；CI/E2E/native 全绿 |
| 2026-11-13 | **方案 A 课次表格 gate 恢复与 advisory 接线切片完成**（PR #115, `e8c0d999`） | 恢复 `check_lesson_tables.py`/`test_lesson_tables.py`，`verify-e2e.sh` advisory 接线并同步 `test_gate_semantics_selftest.py`；CI/E2E/native 全绿 |
| 2026-11-13 | **方案 A native 分配实证 gate 恢复与接线切片完成**（PR #116, `432395fd`） | 恢复 `check_native_alloc_evidence.py`/`test_native_alloc_evidence.py`，`verify-e2e.sh` 前置实证门禁接线（rc=1 阻断、rc=2 未判定不阻断）并同步语义自检；CI/E2E/native 全绿 |

## 当前状态（2026-11-13，基于 `origin/master@432395fd` vs `archive/local-lineage-2026-11-08`）

### 1. 两条 lineage 仍互不包含

```bash
git rev-list --count master origin/master   # 本地 lineage: 984 / 远端持续演进
git merge-base master origin/master         # 81e9bf0c
```

### 2. 文件级差异（客观口径，已刷新）

```bash
git ls-tree -r --name-only archive/local-lineage-2026-11-08 | sort > /tmp/A
git ls-tree -r --name-only origin/master                        | sort > /tmp/B
comm -23 /tmp/A /tmp/B | wc -l   # 100  仅本地 lineage 有
comm -13 /tmp/A /tmp/B | wc -l   # 96   仅 origin/master 有
```

**仍仅本地 lineage 有（关键项）**：
- `deploy/tests/` 大量门禁/守护脚本仍缺失（archive 66 vs origin/master **44**；缺 **25** 个，含 `check_*`/`test_*` 守护与判定脚本）
  - 已在 `origin/master` 恢复/存在的 lineage 相关项包括：`quick_audit.sh`、`test_gate_wiring.py`、`test_gate_semantics_selftest.py`、`test_jacoco_phase_wiring.py`、`test_attr_restore_pattern.py`、`test_env_example_secrets.py`、`test_internal_call_verifier_wiring.py`、`test_prometheus_rule_files.py`、`test_k8s_probe_paths.py`、`test_image_tag_expansion.py`、`test_dockerfile_jvm_flags.py`、`test_probe_timeout_guard.sh`、`check_port_conflicts.py`、`test_port_conflicts.py`、`test_docs_probe_scripts.py`、`check_compose_dangling_deps.py`、`test_compose_dangling_deps.sh`、`check_otel_pipeline_refs.py`、`test_otel_pipeline_refs.sh`、`check_lesson_tables.py`、`test_lesson_tables.py`、`check_native_alloc_evidence.py`、`test_native_alloc_evidence.py` 等
- `cloud-infra-test-support/.../diagnostics` 宿主负载诊断夹具
- `file-service` / `gateway` / `system-module` 等剩余加固测试与 domain 视图

**仍仅 origin/master 有（关键项）**：
- 3 个 `deploy/tests` 脚本为 `origin/master` 独有（`prepare_evidence_fixture.py`、`run_evidence_it.py`、`test_resilience4j_binder_present.py`），与 lineage 集合不一致
- `manifests/base/**`、部分历史 dashboard 文件等与本地 lineage 重构/删除不一致的残留

### 3. 已解除 / 已修复 / 已落地

- ✅ **部署阻断（方案 B）**：`V24/V25/V26` 已恢复并与 `flyway_schema_history` 校验和一致；`ErrorCodeController` 与 V25 种子成对受 `@PreAuthorize` 保护（#87）。
- ✅ **超管旁路失效**：`PermissionService` 修复 `SUPER_ADMIN` 匹配；`PermissionServiceSuperAdminRoleKeyTest` 守护（#89）。
- ✅ **流式下载异步重派发丢失身份**：`HeaderAuthenticationFilter.shouldNotFilterAsyncDispatch()=false` 及守护（#89）。
- ✅ **quick_audit gate 恢复与 CI 接线**：`quick_audit.sh` 恢复并纳入 e2e workflow（#91/#92）。
- ✅ **gate 接线守护与聚合执行**：`test_gate_wiring.py`、e2e 聚合执行 `deploy/tests/test_*`、`verify-e2e.sh` 裸 `podman inspect` 超时修复、`check_sw_oap_config.sh` 真实非阻断接线（#92）；门禁语义接线自检适配（#107）。
- ✅ **jacoco 覆盖率 gate 落地**：`test_jacoco_phase_wiring.py` 与执行接线恢复并合并（#105）。
- ✅ **零使用残留清理**：`cloud-infra-codegen/**`、`docs/codegen/`、专用 workflow 移除并对齐文档/CI（#94）。
- ✅ **Prometheus 告警规则可加载性与守护**：`gateway-alerts` 原生规则化，`test_prometheus_rule_files.py` 恢复（#101）。
- ✅ **内部调用验签单一真源收敛**：`InternalCallVerifier` + 站点委托迁移 + `test_internal_call_verifier_wiring.py`（#103）。
- ✅ **部署模板密钥去公开化与随机化**：`.env.example`/`deploy/up.sh` 修复，`test_env_example_secrets.py` 守护（#106）。
- ✅ **适用 gate 回补（近期）**：`test_attr_restore_pattern.py`（#95）、`test_k8s_probe_paths.py`/`test_image_tag_expansion.py`（#109）、`test_dockerfile_jvm_flags.py` + Dockerfile 清理（#110）、`check_port_conflicts.py`/`test_port_conflicts.py` + `verify-e2e` 前置接线（#111）、`test_docs_probe_scripts.py`（#112）、`check_compose_dangling_deps.py`/`test_compose_dangling_deps.sh` 与 `check_otel_pipeline_refs.py`/`test_otel_pipeline_refs.sh` + `verify-e2e` 前置阻断接线（#114）、`check_lesson_tables.py`/`test_lesson_tables.py` + advisory 接线（#115）、`check_native_alloc_evidence.py`/`test_native_alloc_evidence.py` + `verify-e2e` 前置实证接线（#116）；`test_probe_timeout_guard.sh` 已存在于 master 且验证通过。

### 4. 仍未收敛（方案 A 后续）

| 缺口 | 现状 | 影响/风险 |
|---|---|---|
| 门禁脚本覆盖 | lineage 仍有 **25 个** `deploy/tests` 脚本未回补；多项 `check_*` 需真实调用点/语义对齐，部分 `test_*` 依赖当前树不存在的函数/资产/配置或对现状判红 | 回归风险仍高于 lineage；盲目拷贝易引入不适配/假门禁或误阻断 |
| 文档/资产偏离 | `docs/analysis/`、`docs/audit/`、`docs/lessons/debugging-log.md` 已恢复；其余 lineage 文档/部分加固测试与 domain 视图仍缺失 | 判据与操作指引仍有分叉，排查/治理资产未完全收敛 |
| 部署态配置收敛 | `check_sw_oap_config` 对当前 SW OAP/search 共用 ES 9.x 配置仍为已知不匹配（非阻断接线告警） | ES/config 未收敛前无法将该守护升为阻断 |
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
| **A. 树收敛** | 分批补回缺失加固、恢复门禁与接线、删除远端残留零引用代码，最终使树一致 | 🔄 进行中（#89 安全、#91/#92/#107 gate 恢复与接线、#94 codegen 清理、#95/#109/#110/#111/#112/#114/#115/#116 适用 gate 回补与接线、#97/#99 docs/lessons+analysis+audit 资产对齐、#101/#103/#105/#106 已落地；剩余门禁覆盖、其余文档/资产与配置/树级残留待分批推进） |
| **C. 重建历史** | 重推本地 lineage | 风险最高，需再次清理密钥泄露；不建议优先 |

> 建议后续按**独立可验证切片**推进 A：优先恢复并接线**当前树直接适用**的 `deploy/tests`（`test_*`/`check_*` 成对、具备真实调用点与负向对照，CI/E2E 全绿）；对依赖缺失函数/资产或与现状不一致的项先做对齐再恢复。`check_sw_oap_config` 应在 ES/config 收敛后恢复阻断；若出现真实平台级审计需求，再按需起 ADR-012（不放宽租户过滤）。

## 复跑证据

```bash
# 文件级差异（当前口径）
git ls-tree -r --name-only archive/local-lineage-2026-11-08 | sort > /tmp/A
git ls-tree -r --name-only origin/master                        | sort > /tmp/B
comm -23 /tmp/A /tmp/B | wc -l   # 100
comm -13 /tmp/A /tmp/B | wc -l   # 96

# deploy/tests 覆盖口径
git ls-tree -r --name-only origin/master -- deploy/tests | wc -l                                      # 44
git ls-tree -r --name-only archive/local-lineage-2026-11-08 -- deploy/tests | wc -l                  # 66
comm -23 <(git ls-tree -r --name-only archive/local-lineage-2026-11-08 -- deploy/tests | sort) \
         <(git ls-tree -r --name-only origin/master -- deploy/tests | sort) | wc -l                   # 25
comm -13 <(git ls-tree -r --name-only archive/local-lineage-2026-11-08 -- deploy/tests | sort) \
         <(git ls-tree -r --name-only origin/master -- deploy/tests | sort)                           # prepare_evidence_fixture.py / run_evidence_it.py / test_resilience4j_binder_present.py

# 近期落地指纹
git ls-tree -r --name-only origin/master -- deploy/tests/test_gate_wiring.py deploy/tests/test_jacoco_phase_wiring.py deploy/tests/check_port_conflicts.py deploy/tests/test_port_conflicts.py deploy/tests/test_docs_probe_scripts.py deploy/tests/test_dockerfile_jvm_flags.py deploy/tests/test_probe_timeout_guard.sh deploy/tests/check_compose_dangling_deps.py deploy/tests/check_otel_pipeline_refs.py deploy/tests/check_lesson_tables.py deploy/tests/check_native_alloc_evidence.py
rg -n "check_port_conflicts" verify-e2e.sh
```

## 关联

- issue #36（PR #79–#85，ADR-011）
- PR #86–#116（方案 B、状态刷新与方案 A 各切片；#105/#108–#116 已合并）
