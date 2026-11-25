# 2026-11-06 修复闭环报告（回合 1：GC / 内部调用密钥 / gRPC 端口 / 采样率）

> 上游依据：`docs/analysis/comprehensive-analysis-2026-10-25.md`（总体画像与设计/性能建议）、
> `docs/perf-audit-2026-11-05.md`（P0/P1/P2 资源与性能建议）。
> 本报告记录本轮「按建议实施」的全部改动、守护、验证事实，以及未收口项。

---

## 一、本轮实施结论速览

| # | 建议项 | 状态 | 关键验证 |
|---|---|---|---|
| 1 | compose GC 回退 `UseSerialGC`（删无效 flags） | ✅ 已实施 | YAML 解析 OK；4 组自检/门禁 rc=0 |
| 2 | 内部调用签名密钥公开弱值拒绝 + up.sh 随机化 | ✅ 已实施 | `cloud-infra-common` 96 tests 0 失败 |
| 3 | gRPC port 死配置修复 + 门禁真空修复 | ✅ 已实施 | 门禁三态通过；`system-module` 118 tests 0 失败 |
| 4 | 模块层 tracing sampling `1.0 → 0.2` 对齐 | ✅ 已实施 | YAML 可解析，4 处改动 |

## 二、本轮实施细节

### 2.1 GC 回退（P1 性能 / 交付一致性）

- 改动面：`deploy/compose.yaml` 中 6 处 `UseSerialGC` → `UseG1GC`，删除 SerialGC 下无效的
  `-XX:+UseStringDeduplication` / `-XX:MaxGCPauseMillis=200`。
- 原因：SerialGC「不适合生产流量」被 `OPTIMIZATION_PLAN_2026_08` 明确记录；原 compose 行含两个
  不生效 flag，回退是与 Dockerfile / Helm / ADR / AGENTS.md 四面一致的修正。

### 2.2 内部调用签名密钥（P1 安全）

- `cloud-infra-common/.../InternalCallCrypto.java`：新增公开弱内部调用密钥
  SHA-256 指纹黑名单（不落明文），命中即 `IllegalStateException`（fail-fast）。
- `cloud-infra-common/.../JwtUtils.java`：弱密钥黑名单新增 `.env.example` 曾用的 JWT 占位值指纹
  （`11474eaa2...`），命中即拒绝。
- `deploy/up.sh` `ensure_env()`：新增 `rotate_secret` helper，统一覆盖三种形态 —— 无 `.env`、
  存量 `.env` 为公开占位值、键行缺失。JWT（占位 `your_very_secure_jwt_secret_key_at_least_32_chars`）
  与内部调用密钥（占位 `cloud-infra-internal-dev-secret`）两条全部随机化。
- `.env.example`：`AUTH_JWT_SECRET` / `INTERNAL_CALL_SECRET` 均留空。
- 新增守护：`cloud-infra-common/.../crypto/InternalCallWeakSecretTest.java`（公开弱拒绝 + 高强度放行）。
- 新增部署侧守护：`deploy/tests/test_env_example_secrets.py` —— 行为级（抽取真实 `ensure_env` 执行），
  覆盖 A/B/C 三种形态；两项负向对照（删随机化调用 / 回填模板占位值）精确红，恢复绿。

### 2.3 gRPC 端口（P1 正确性）

- 真相：`@ConfigurationProperties(prefix = "cloudinfra.grpc")` 上的**生效键是扁平**的
  `cloudinfra.grpc.port`（properties 类**没有** `Server` 子类）。
- 本轮改动：`cloud-infra-modules/system-module/src/main/resources/application.yml` 中整个
  `spring.grpc.*` 块（死键）删除，`cloudinfra.grpc.port: ${CLOUDINFRA_GRPC_PORT:9090}` 生效。
- 说明：删除前实际生效值即为默认值（9090 / 4MB / 30s），故本次**不改变运行时行为**，
  只把端口变为可覆盖，消除「gRPC 与 gateway 同 9090」的 BindException 风险。

### 2.4 门禁真空缺陷（高价值教训）

- 发现：`deploy/tests/check_grpc_port_placeholder.py` 存在**两层路径错误**（`parents[1` 取错、
  模块路径少一层），从不扫描任何文件，历史「OK」全是假绿。这是本仓库已多次复出的第 3/4 类
  「存在但未生效」缺陷。
- 修复：改用 PyYAML 解析 + 键路径语义判定；三态验证：
  - 正向（有效键 + 占位）→ rc=0
  - `port: 9090` 裸值 → rc=1
  - `spring.grpc.server.port` / `cloudinfra.grpc.server.*` 死键 → rc=1
  - 恢复 → rc=0

### 2.5 tracing sampling 基线对齐（P0 尾缺）

- `system / notification / search / file` 四个模块 `application.yml` 的
  `management.tracing.sampling.probability: 1.0` → `0.2`，与 `deploy/config/*`、`deploy/k8s/**`
  已一致的 0.2 对齐。gateway 模块本身无 tracing 块（走 actuator 默认）。
- demo-service 保留 1.0（demo 模块，`-Pwith-demo`，非生产）。

## 三、交付口径与未收口项

1. **全量 IT 单次全绿**：第二次全量运行 1 次 `BUILD FAILURE`（仅 search-service
   `SearchServiceIntegrationTest` 测试容器启动超时，环境性；模块重试 **BUILD SUCCESS**）。
   **第三次全量运行（最终源码状态）`BUILD SUCCESS` —— 106 IT tests, 0 failures/errors/skipped**，
   20 个模块全部 SUCCESS。该运行确认了 GC 回退 / 密钥加固 / gRPC 端口 / 采样率全部改动
   在全链路集成测试下无回归。
2. **全量单元测试**（`mvn -o clean test`，最终源码状态）：**594 tests, 0 failures, 0 errors, 1 skipped**，
   `BUILD SUCCESS`。影响模块（common 97 / framework-grpc 17 / system-module 118）亦单独全绿。
3. **提交拆分**：未做（截至 2026-11-08 收尾共 **219 项**未提交改动；提交边界需用户确认）。
4. **CI / 分支分叉**：本地 master 与远端已分叉，历史因 `git filter-repo` 被重写；
   E2E pipeline 最近一次为计费阻塞（steps=0）；HEAD 无 CI 运行。
5. **P2 perf audit**：native 二进制堆 `-J-Xmx4g` → `-J-Xmx512m` 待定（需受控实验 / 用户确认方向）。
6. **P1 perf audit**：Dockerfile 对齐 SerialGC 未做（已决策不采纳，需受控实验）。

## 四、给下一轮的结论

- 本轮新增/改动的守护均已**有行为级或语义级负向对照**证明有效（改坏 → 红 → 还原 → 绿）。
- 全新发现与修复的缺陷：`.env.example` 公开 JWT 占位值未被随机化/拒绝、
  up.sh 随机化覆盖面不足、`check_grpc_port_placeholder.py` 假绿。
- gRPC 的「生效键位置」教训：配置前缀与 properties 结构必须以代码为准核对，
  只改键名而不改层级（`server.port` 与 `port` 是两种键）会继续留下死键。

## 五、2026-11-07 增补：第二轮守护真空（同类缺陷第 4/5/6 处）

对「manifests/ 删除后残留引用」的核查又查出两个门禁的**声明-实际不符**假绿:

| 门禁 | 缺陷 | 修复 |
|---|---|---|
| `test_k8s_probe_paths.py` | `SCAN_ROOTS` 仍声明已删除的 `manifests/`；`iter_yaml_files()` 对不存在目录**静默 continue** → 少扫一整块继续 PASS；且 `checked=0` 仍会 PASS | 移除该扫描根；新增"声明的扫描根必须存在"与"扫描到 0 处探针必须报红"两条硬判定 |
| `test_es_version_alignment.py` | `SERVER_CONFIGS` 仍含已删除的 `manifests/base/elasticsearch.yaml`；`if not f.exists(): continue` 静默跳过 → "三处核对"实际只剩两处，且路径写错也无感 | 移除该条目；改为**声明的配置必须全部解析到位**，缺失即报红 |

负向对照（全部实测）：
- probe 指到不存在扫描根 → `FAIL: 声明的扫描根不存在` (rc=1)
- probe 扫到 0 处探针 → `FAIL: 扫描到 0 处探针` (rc=1)
- ES 声明配置缺失 → `FAIL: 声明的 ES 服务端配置未全部解析到位` (rc=1)
- 还原后均转绿 (rc=0)

**通用规则（同 AGENTS.md「豁免必须附验证」）**：门禁脚本里**声明的扫描对象**是可核实的
断言 —— 每写死一个路径, 就要同时写一条"若它不存在必须报红"的判定;
否则任何一次部署面迁移都会静默降低守护覆盖面。

## 六、配套产出

- `docs/analysis/2026-11-07-commit-split-plan.md`：206 项改动的提交拆分计划（**未执行任何提交**）。

## 七、2026-11-08 增补：verify-e2e.sh「阻断形态」接地（第三轮，同一根因的门禁侧）

### 发现

`verify-e2e.sh` 第 15 行是 `set -uo pipefail`，**没有 `-e`**。在该语义下：

- **裸调用不中止**：`check_schema_standalone` / `check_container_startup_budget_gate` /
  `check_es_wait_strategy` 三处是真裸调用 —— 失败时函数 `return 1` 只影响退出码，
  E2E **继续往下跑**。运行时实证：
  `bash -c 'set -uo pipefail; f(){ return 1; }; f; echo 裸调用不中止; f || exit 1; echo 不会打印'`
  只输出第一行, rc=1。
- **self-test 判据与运行形态脱节**：旧的阻断判据把「独占一行的裸调用」当作合法
  阻断形态(a)（其注释声称 "return 1 即中止" —— 那只在 `set -e` 下成立）。
  即自检一直在为三个从未真正阻断的门禁背书。
- **5 处 `|| true` 吞掉了自我声明阻断的门禁**：`check_compose_dangling_deps`
  （函数注释明写"为什么阻断…不该像 check_jvm_opts_keys 那样降级为 || true"）、
  `check_otel_pipeline_refs`（"阻断而非降级"）、`check_port_conflicts`（rc=1/2 均 fail,
  旧调用区注释却把它误归入"文档可信度"类）、`check_native_alloc_evidence`
  （设计：rc=1 实证失败→阻断）、`check_grpc_port_placeholder`（纯静态, 本轮决策）。
- **`check_commit_runtime_guard || exit 1` 的文案矛盾**：函数 fail 文案与调用区
  echo 均写"仅告警/非阻断"，接线却是退出 —— 定性为阈值校准时代的遗留文案
  （85% 会误伤 82%~91% 正常区间 → 当时确实只能告警；现在阈值 92% 落在正常区间与
  崩溃点 99.6% 之间的空档, 阻断不会误报）。**保持阻断**，文案更正并加历史备注。
- **`warn` 从未定义**：`check_schema_standalone` 的 rc=3 分支调用 `warn`（脚本只有
  ok/fail），实际会打 "command not found" —— 改为显式 `echo … >&2`。

### 修复（verify-e2e.sh）

1. 8 处调用接地为 `check_xxx || exit 1`（三个裸调用 + 五个 `|| true`）。
2. self-test 阻断判据重写：**只认两种显式形态** ——
   (a) `check_xxx || exit 1`；(b) `if ! { …; check_xxx; }; then`。裸调用不再认可；
   清单从 2 个扩到 **11 个**阻断门禁（新增 schema_standalone / startup_budget /
   es_wait_strategy / compose_dangling_deps / otel_pipeline_refs / port_conflicts /
   v18_watchdog / commit_runtime_guard / native_alloc_evidence / grpc_port_placeholder）。
3. `check_commit_runtime_guard` 两处陈旧文案更正 + 历史备注（防止被旧文案"改回去"）。
4. 原 `warn` 调用改为显式 stderr 输出。

### 负向对照（全部实测）

| 对照 | 期望 | 实测 |
|---|---|---|
| `es_wait_strategy` 改回 `|| true` | 阻断清单报红, rc=1 | rc=1 |
| `v18_watchdog` 改回裸调用 | 报"没有阻断形态", rc=1 | rc=1（裸调用不再被认可是关键断言） |
| `grpc_port_placeholder` 改回 `|| true` | 报红, rc=1 | rc=1 |
| 还原（备份） | self-test 全绿 | rc=0, 阻断清单 11/11 PASS |
| 连带守护复跑 | 无假红 | schema_init_wiring / probe_timeout_guard / start_period_guard / gate_wiring(未引用 0) 全绿 |

### 待定项（已于第八节收口）

第八节复核后：`check_schema_init_wiring` / `check_start_period_covers_startup` /
`check_feign_timeout` 收归阻断；`check_catch_exception_count` 维持 advisory
（代码质量指标，非运行时前置）。原 4 项待定至此全部有明确归属。

### 通用规则（本项目第九次同型教训）

**判据只核对"文本长得像阻断"是不够的 —— 判据必须匹配运行语义。**
`set -uo`（无 `-e`）下，裸调用的 `return 1` 与止不住的「卡在继续往下跑」是同一个东西；
而自检判据引用的语言事实（"裸调用失败会不会中止"）**来自 bash 而不是来自脚本文本**。
新增阻断门禁时：接线用显式 `|| exit 1`，self-test 阻断清单同轮更新，负向对照两种
形态各做一次（`|| true` 吞掉 / 裸调用降格）。

> **口径**：测试通过 ≠ 已交付；全量 IT 单次全绿是后续交付判定前提。

## 八、2026-11-08 增补：第三批假门禁（第 10~14 处，路径漂移 + 逻辑自矛盾）

### 发现方式

按「探针根目录只差一层」做全仓系统性扫描（正则匹配 `ROOT/REPO = parents[1]`、
`dirname(__file__)`），命中 5 个脚本，全部位于 `deploy/tests/`，且都在用这些根
拼**仓库级路径**：

- `parents[1]` 从 `deploy/tests/xxx.py` 只到 `deploy/` —— 拼出的
  `deploy/cloud-infra-modules/...`、`deploy/cloud-infra-api` 均不存在；
- `dirname(__file__)` 就是 `deploy/tests/` —— 拼出的
  `deploy/tests/cloud-infra-api` 同样不存在。

扫描根不存在时，5 个脚本全部**静默扫到 0 个目标并打印 OK**。这与第五课
「没验到 ≠ 验过了」、以及第七节的「定义存在 ≠ 被调用」是同一根因的第三次变体。

### 逐项缺陷与修复

| # | 脚本 | 假绿机制（实测） | 修复后判据 |
|---|---|---|---|
| 10 | `check_feign_timeout.py` | `REPO_ROOT = dirname(__file__)` → `API_ROOT=deploy/tests/cloud-infra-api`（不存在）→ **永远 0 个客户端 PASS**；文案声称"或 yml 全局声明"，但代码从未读过任何 yml | `ROOT=parents[2]`；扫描到 0 个 `@FeignClient` → rc=2；覆盖形态 = 注解超时 **或** 消费服务的 `deploy/config/<svc>.yaml` / `application*.yml` 声明 `openfeign + connectTimeout + readTimeout`；**无消费方**的客户端无调用路径，列 INFO 不阻断 |
| 11 | `check_nacos_config_alignment.py` | 根目录浅一层导致本地 yml 全部 `continue`；且用**扁平字面量** `spring.cloud.nacos.discovery` 匹配**嵌套 YAML** → `deploy_has` 恒 False；打印的是声明数而非实核数 | PyYAML 载入后按嵌套路径语义判定；三组子块（nacos.discovery / management.endpoints / datasource.hikari）在本地或部署**至少一处**存在；声明文件缺失 → rc=2；实核 0 个 → rc=2 |
| 12 | `check_shared_config_alignment.py` | 根目录浅一层 → rglob 0 个文件仍打印 OK；demo 排除判据 `svc.name[:-4]=="demo"` 恒为 `"application"`，从未生效；按文件计数把 1 个服务算成 8 个；声明"所有 service"却只扫 modules，auth/gateway 从未核对 | 按服务去重（`-dev/-prod` 变体合并）；扫描范围 = `cloud-infra-modules/*` + auth + gateway；本地或部署配置任一处 import 即通过；0 个 service → rc=2 |
| 13 | `test_attr_restore_pattern.py` | 根目录浅一层 → 4 个扫描根全不存在，0 违规 OK；检测逻辑**自相矛盾**：前置要求 `System.setProperty` 出现，随后调用要求它**不出现**的函数 → 即使路径修好也永不判红 | 先剥离注释（避免 javadoc 里的历史说明被当调用）；安全形态两种：捕获式 `String o=clearProperty(K)` 且后文用 `o` 还原，或条件式 `if (o==null) clearProperty(...)`；其余裸 clear 判红；0 个文件 → rc=2 |
| 14 | `check_openapi_meta_coverage.py` | 根目录浅一层；且逻辑上**永不可能判红**（遍历 glob 出来的文件必然存在；缺失锚点只"记录在 issue"）。前提本身虚构：全仓无 Info/Contact/License/SecurityScheme，springdoc 仅 gateway 聚合、无元数据配置 | **删除**（符合"不得为凑完整补配套"）；`verify-e2e.sh` 中函数定义与调用一并移除 |

### 修复后真实仓库结果（均 rc=0）

- feign：4 个无注解客户端由消费服务全局超时覆盖；`SearchApi` 无任何消费方
  （仅注释与自身 fallback 引用）→ INFO。
- nacos：4 个服务三组子块均有本地/部署覆盖（`notification` 的 hikari 仅在部署、
  `search` 的 hikari 仅在本地，均属合规的"至少一处"）。
- shared：6 个服务 —— file/notification/search/auth/gateway 本地 import，
  system-module 走 legacy nacos 机制、import 声明在
  `deploy/config/system-module.yaml`（部署侧覆盖）。
- attr：扫描 4 个含 `clearProperty` 的测试文件，均为条件式/捕获式还原。

### 负向对照（全部实测）

| 脚本 | 改坏 | 实测 | 扫描根漂移 | 实测 |
|---|---|---|---|---|
| feign | 删 `deploy/config/system-module.yaml` 的 `readTimeout` | rc=1，精确报 `NotificationApi`（system-module 唯一消费的客户端） | 副本指向不存在根 | rc=2 |
| nacos | fixture 中 search 本地与部署都缺 hikari | rc=1，精确报 search-service | 声明文件缺失 | rc=2 |
| shared | fixture 中 beta 两处都无 import | rc=1，精确报 beta-service | modules 目录缺失 | rc=2 |
| attr | fixture 含裸 `System.clearProperty` 不还原 | rc=1，报 L4 | 空 fixture | rc=2 |
| openapi | —— | 已删除（不再存在"永不判红"脚本） | —— | —— |

每组均做「改坏→红→还原→绿」，还原后真实仓库全部 rc=0。

### 门禁定性收口

- **收归阻断**：`check_schema_init_wiring`（缺执行机制 → 表不存在却 healthcheck 仍绿）、
  `check_start_period_covers_startup`（start_period 不足 → 服务可用但永远 unhealthy）、
  `check_feign_timeout`（无生效超时 → 拖死网关线程）。三者纯静态、缺陷直接影响运行时，
  调用改为 `|| exit 1`；self-test 阻断清单 **11 → 14**。
- **维持 advisory**：`check_catch_exception_count`（代码质量指标，非运行时前置）；
  `check_nacos_config_alignment` / `check_shared_config_alignment`（显式声明为非阻断对齐告警）。
- **删除**：`check_openapi_meta_coverage`（前提虚构且逻辑永不判红）。

### 接线负向对照（new）

| 对照 | 期望 | 实测 |
|---|---|---|
| `check_feign_timeout` 改回 `|| true` | self-test 报红 | rc=1，精确报该门禁 |
| `check_schema_init_wiring` 改回裸调用 | self-test 报红 | rc=1，精确报该门禁 |
| 还原 | self-test 全绿 | rc=0，14/14 阻断形态 PASS |

### 回归验证（2026-11-08 最终态）

- `verify-e2e.sh --self-test`：**rc=0，14/14 阻断形态 PASS**。
- `test_gate_wiring.py`：**63 个脚本 / 38 个应被调用 / 未被引用 0**。
- 全套 `deploy/tests/test_*`（CI 同款分流 `.sh→bash`、`.py→python3`）：
  **39 项，38 项 rc=0**；`test_v18_migration.sh` 首轮 rc=1 的根因是
  **我自己的批跑器超时后遗留了两个旧实例**（前台 120s 超时未杀进程组），
  与后台批跑同时操作固定测试库 `v18_migration_test` —— 日志表现为
  "database already exists" 紧接着 "does not exist"，属装置并发碰撞，
  不是迁移缺陷。清理遗留 pid 后单独复跑两次，均 `RESULT: ALL_PASS`
  （5 项 PASS），最后一次 **RC=0**。
- 操作性教训：批跑器超时必须杀**进程组**；固定测试库名 +
  残留实例 = 假红/假绿都可能的装置缺陷（本次是假红）。

### 通用规则（本次新增三条）

1. **每个扫描器必须断言「目标数 > 0」，且负向对照必须包含"扫描根缺失"一路。**
   只做"注入缺陷 → 应变红"是不够的：路径漂移时注入的缺陷根本不在扫描范围内，
   脚本仍然绿。0 命中要区分"仓库真的没有"与"探针坏了"，前者需显式豁免。
2. **判据的两个条件若互斥，等于永不判红 —— 必须用负向对照证伪一次。**
   `test_attr_restore_pattern` 的"要求出现"与"要求不出现"同时成立不可能，
   这种缺陷读代码时极易被忽略，只有"改坏应红"能暴露。
3. **修复路径 bug 后若出现误报，优先核对计数口径**（按文件 vs 按服务/实体），
   不要把"一个服务 8 个变体"当成 8 个服务汇报。
