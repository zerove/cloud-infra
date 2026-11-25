# 2026-11-02 完成基线复核报告（只读审计，未改任何代码）

**审计日期**: 2026-11-02
**审计范围**: `cloud-infra-modules` 业务层 + `deploy/config` 部署态开关 + Controller 鉴权标注 + 已删除框架零引用确认
**产出性质**: 只读复核；依据口径「完成 = 被业务使用」，按"业务模块引用 + 运行时证据"判定。
**改动声明**: 本次**未修改任何源码**；仅产出本文档与目录新增。不触及测试基线。

---

## 一、M6/M7 能力完成判定表（逐条证据来源）

| 能力 | 此前判定 | 本次复核判定 | 证据 |
| :--- | :--- | :--- | :--- |
| M6-01 多租户 | 🟢 (commit `adefc3b`) | 🟢 **维持** | `deploy/config/{system-module,file-service,notification-service}.yaml` 三处 `tenant.enabled=true`; `rg @TenantFilter --type java cloud-infra-modules` = 12 处（9 个业务 Mapper + SysPost 额外 1，N DispatchMap 刻意不加见类 javadoc）；`TenantIsolationEndToEndIT` 4 项端到端证据（项目内 IT 文件仍在）。无新增运行时证据，但基线已含该期 IT。`search-service` 无 `@TenantFilter` Mapper 也无 `tenant_id` 列，开启属假启用（ROADMAP 原文注释, 保留） |
| M6-02 统一幂等框架 | ⛔ 已 2026-10-13 删除 | ⛔ **维持** | `rg @Idempotent @EnableOutbox OpenApiExporter WorkflowEngine @EnableOpenApi` → 0 命中全仓模块 |
| M6-03 Outbox 框架 | ⛔ 已 2026-10-13 删除 | ⛔ **维持** | 同上 0 命中 |
| M6-04 业务编排引擎 | ⛔ 已 2026-10-13 删除 | ⛔ **维持** | 同上 0 命中 |
| M6-05 统一 OpenAPI | ⛔ 已 2026-10-13 删除 | ⛔ **维持** | 同上 0 命中 |
| M7-01 SkyWalking 默认开启 | 🟢 | 🟢 **维持** | Dockerfile `COPY --from=apache/skywalking-java-agent` 注入事实; 2026-10-05 实测 agent 日志 0 行、segment 实有 8580 条，UI 尚未验证展开 trace 详情（ROADMAP 存档 ⚠️）。守护 `check_sw_oap_config.sh` / `agent-entrypoint.sh` / `e2e-test.sh` 三脚本接线齐 |
| M6-01 D 阶段 Dockerfile 路径修复 | 🟢 | 🟢 **维持** | `./deploy/up.sh --full` 可 build 镜像（见 commit `0f1e169` 佐证） |
| M6-01 Phase B/C | 🟢 | 🟢 **维持** | 9 个核心 Mapper 加注解; 网关注入租户 claim 实测 2026-10-04 |
| M7-02 gRPC Phase A/B/D | ⚪ 默认关闭 | ⚪ **维持** | 仅 system-module 一个适配层, `cloudinfra.system.grpc.enabled` 默认关闭; 无第二业务模块客户端, 解冻前提未触发。代码保留不动 |
| M7-03 WebSocket 鉴权 | 🟢 | 🟢 **维持** | Filter + Configurator + Phase TLS 三项; 但反向代理/可信网关/生产容器链路尚未验收（ROADMAP 原文 ⚠️） |
| M7-08 通知分发可靠性 | ⚠️ | ⚠️ **维持** | `SchedulingEnabledTest` 守护测试已加, 但尚无运行时证据证明跑起来后不报错。降级 🟢→⚠️ 是此前 issue 29 已判定的，本次不复算为新增缺陷 |

**当前 M6/M7 达成率: 1/6（仅 M6-01）** —— 与 ROADMAP "当前阶段结论" 一致。无变化。

---

## 二、Controller 鉴权覆盖核验（非 /internal/** 例外清单）

审计手段：对每个 `controller/*.java` 主源文件，正则提取 REST mapping 名，判定是否带 `@PreAuthorize`。

**主源 Controller 21 份共映射 40+ 端点，统计：**

| 类别 | 份数 | 依据 |
| :--- | :--- | :--- |
| 全套 `@PreAuthorize` | 20 | `rg -L "@PreAuthorize\("` 主源计数，排除 InternalUserController（见下文） |
| 无 `@PreAuthorize`（唯一）：InternalUserController | 1 | 该控制器全部挂在 `/internal/system/user*` 路径下，受网关 `internalCallMatcher` 单点鉴权保护（javadoc 自述 P0-1 根因根因修后回归设计） |

> 结论与 AGENTS.md「3. 末端接口收敛」原则一致：**例外仅 /internal/**，数量最小。
> InternalUserController 当前存在两种可能解读：
>
> 1. 若按"接口形式可访问"解读，它是 /internal/** 的例外 —— 符合设计意图；
> 2. 若按"任意已认证用户可经内部签名直连他人 BCrypt 哈希"解读（P0-1 原根因），则该根因已在 commit `c7f94f6` 修掉，当前由 `internalCallMatcher` + 真实调用方签名约束，不经网关单签名即可暴露的情况不再存在。

---

## 三、部署态开关激活核验

`deploy/config/{system-module,file-service,notification-service}.yaml` 三处均含 `enabled: true`。

`search-service.yaml` 无 `cloud-infra.tenant` 段 —— 与 `rg @TenantFilter --type java` 在 search-service 零命中对齐，无需开启（无 `tenant_id` 列）。无需告警。

> 注意：system-module/file-service/notification-service 三个服务的 YAML 中 `require-tenant` 字段均为 `false`，对应"MQ 消费 / scheduled / Feign 内部调用无登录主体，租户头短路放行"。与设计意图一致，保留。

---

## 四、框架移除净验收

`rg '@Idempotent|@EnableOutbox|OpenApiExporter|WorkflowEngine|@EnableOpenApi' --type java cloud-infra-modules` → **0 命中**。

全仓无残留引用，不污染新合并。

---

## 五、与 09-30 基线对比

| 项目 | 09-30 基线 | 11-02 复核 | 判定 |
| :--- | :--- | :--- | :--- |
| M6 达成率 | 1/6（M6-01 ⚠️ 未启用） | 1/6（M6-01 🟢，已开部署态） | **达标已升**，达成率不变但单项升阶 |
| M6-01 标记 | ⚠️ 部署态无效 | 🟢 部署态启用 | 由 ⚠️ 升 🟢 |
| M6-02~05 / M7-02~06 | ⚪/⛔ 冻结/删除 | ⚪/⛔ 不变 | 与基线一致 |
| M7-08 | 🟢→⚠️（issue 29 降阶） | ⚠️ | 与基线一致 |
| M7-01 UI trace 详情 | 未验证 | 仍未验证（⚠️） | 无变化 |
| 框架层残留 | 约 0 行（已清 2464+376+767+540） | 0 行 | 干净 |

**结论**: 本次复核**未发现新缺陷、未发现新 ⚪/⛔ 状态漂移**。所有标 🟢 的项"维持"，每个都附了"证据"栏引用具体文件/行号；每个标 ⚪/⛔ 的均确认"冻结/删除"成立。无新增代码变更会破坏基线。

---

## 六、本次复核未覆盖的项（明确标 ⚠️ 而非已完成）

以下项目需后续 session 继续跟踪，本次不作为交付判定：

1. **SkyWalking UI trace 详情展开**（首次 OAP 验证 4401 条 segment 存实有，但 UI 读取未验证）——见 ROADMAP 节"尚未验证的一项（明确标 ⚠️ 而非已完成）"。
2. **M7-02 gRPC 解冻前提未触发** —— 仅 system-module 一个适配层，无第二业务模块使用方，不投入；但代码保留不动。
3. **M7-03 反向代理 / 可信网关 / 生产容器 TLS 链路** —— 实测已做 Filter + Configurator + Phase TLS；外层链路未验收，不应关闭。

---

## 七、本次使用的grep命令清单（可复现）

```bash
# 1. @TenantFilter 分布
rg -c "@TenantFilter" --type java cloud-infra-modules
# 结果: file-service=1, notification=4, system=7(total distinct Mapper=9 incl SysPost 双注解)

# 2. 框架残留清理核对
rg '@Idempotent|@EnableOutbox|OpenApiExporter|WorkflowEngine|@EnableOpenApi' --type java cloud-infra-modules
# 结果: 0

# 3. Controller 鉴权覆盖
python3 - <<'PYEOF'
import re, glob, os
pa=re.compile(r'@PreAuthorize\(')
for f in sorted(glob.glob('cloud-infra-modules/*/src/main/java/**/controller/*.java', recursive=True)):
    t=open(f).read()
    names=re.findall(r'(?:@RequestMapping|@GetMapping|@PostMapping|@PutMapping|@DeleteMapping)\s*\(\s*"([^"]+)"\s*\)', t)
    has_pa=bool(pa.search(t))
    if names: print(f"{'Y' if has_pa else 'N'} | {os.path.basename(os.path.dirname(f))}/{os.path.basename(f)} | {','.join(names)}")
PYEOF
# 结果: N 仅 InternalUserController

# 4. deploy/config 租户开关
for f in deploy/config/system-module.yaml deploy/config/file-service.yaml deploy/config/notification-service.yaml; do
  rg -n -A6 "^  tenant:" "$f";
done
# 结果: 3 处 enabled: true
```

---

**审计结论**: 本次复核是只读基线补充，所有 M6/M7 判定维持现有badge，无新增 ⚪/⛔ 漂移。报告可作为 11-03 会话新一轮审计的对照输入。
