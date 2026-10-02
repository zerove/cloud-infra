# Cloud-Infra 平台代理指南 (AGENTS.md)

本文件为 OpenCode 代理提供该仓库的快速上手指南、核心指令及架构上下文。

> **当前状态**: v1.0.0 (2026-09-01, 已发布) + M7-01 SkyWalking / M7-03 WebSocket 真实落地; M6 中仅多租户 (9 个业务 Mapper) 真正被业务使用, 幂等/Outbox/编排/OpenAPI/Dashboard 均为 `⚪ 已实现未启用`
> **进度口径**: 按“完成 = 被业务使用”标准, M6/M7 达成率 **1/6**（不是 ROADMAP 上的接近全绿）。详见下方“项目级完成定义”
> **当前阶段**: 存量已足够, **不新增能力**, 转入价值验证期
> **M5 里程碑**: 9 个 P0/M5-01..07 issues 全部 closed, 全部关键能力经过运行时实测
> **M6 里程碑**: 6 项能力代码完成, 但仅 M6-01 多租户有业务引用。其余 5 项业务零引用, 已降级为 `⚪`
> **M7 里程碑**: M7-01 SkyWalking (agent 真实注入) + M7-03 WebSocket Phase A/B 完成; M7-02 gRPC 已冻结 (仅 1 个适配层、开关默认关闭、无第二业务模块, 暂停投入); M7-04/05/06 已冻结
> **完整交接**: [docs/HANDOFF-2026-09-01.md](docs/HANDOFF-2026-09-01.md) (v1.0.0 正式版)
> **session 总结**: `.scratch/2026-q3-roadmap/SESSION-SUMMARY-2026-09-29.md`

---

## 🎯 项目级完成定义 (2026-09-30 新增, 最高优先级)

**完成 = 被业务使用。** 这是本项目一切交付判定的唯一标准。

| 标记 | 含义 | 判据 |
| :--- | :--- | :--- |
| `🟢 Completed` | **已交付** | 业务模块存在**非测试**引用 **+** 有运行时证据 |
| `⚪ Built, Unused` | 代码存在且自测通过 | 业务零引用 → **不构成交付**, 不得计入完成数, 不得在其上继续构建 |
| `⛔ Frozen` | 刻意暂停 | 附解冻前提, 达标前不投入 |

三条硬规则:

1. **组件单元测试不是完成依据**, 只证明它能自我运转。写 17 项自测的编排引擎
   与 0 项自测但被 9 个 Mapper 使用的多租户, 在本定义下得分相同。
2. **进度汇报必须同时给两个数字**: 传统口径 (条目打勾数) + 本定义口径 (被使用数)。
   只报前者会系统性高估。
3. **不得为凑“完整”补配套**: 无使用方的聚合脚本 / 导出器 / 面板 / 文档一律不建。

**为什么**: 本项目长期用“组件自测通过”作完成依据, 导致 11 项 M6/M7 能力中 10 项
建成无人使用, 且框架层 (13843 行) 已厚过业务层 (12138 行)。根因不是某次决策,
而是完成判定标准 —— 没有真实业务倒逼时, 刷内部指标会自然取代交付价值。

详见 `ROADMAP.md` “按被使用标准重算” 与 issue 28。

---

## 🔬 第三条硬规则: "能力存在" ≠ "能力在运行" (2026-09-30 新增, 强制)

2026-09-30 对存量做实证审计 (反射扫描 + 真实容器集成测试 + 基线对照),
查出**四处被标记为已交付、实际从未生效**的能力, 且各自的自测全绿:

| 缺陷 | 机制存在吗 | 生效吗 | 自测为何全绿 |
| --- | --- | --- | --- |
| 4 个 `@Scheduled` 任务从未执行 | 注解、调度器框架都在 | 否 —— 全仓缺 `@EnableScheduling` | mock 直接调方法, 绕过触发 |
| Outbox 的 opt-in 失效 | `@ConditionalOnProperty` 在 | 否 —— stereotype 绕过它 | 只测组件自身装配 |
| 内部签名形同虚设 | HMAC 验签在 | 否 —— 网关给每个请求都签名 | 只测签名函数本身 |
| auth 服务启动失败 | 自动配置类在 | 否 —— 从未登记进 `imports` | 单测手工 `new` 切面 |

**因此, 交付前必须回答"它在运行时到底走没走这条路径", 而不是"代码在不在"。**
最低要求 (三条同时满足才算有运行时证据):

1. **结构性证据**: 反射/配置层面能证明链路被接上 (注解、条件、装配顺序), 且有守护测试防止回归。
2. **触发证据**: 有测试验证"触发条件成立 → 代码真的执行", 而非直接调用目标方法。
3. **端到端证据**: 真实进程/容器跑通一次 (或明确标注"尚无", 记 ⚠️ 而非 🟢)。

**CI 红灯会制造检测盲区**: P0 级的“auth 启动失败”正是被它掩盖的。
叠加本地 `skipITs=true`, 该缺陷自引入起确实没有任何一处真正编译或测试过。
新增需要跨进程/容器验证的能力时, 必须同步给出该能力在默认构建里的验证路径,
并在 CI 恢复绿之前**手工跑一次并把结果写进交付记录**。

**⚠️ 下方诊断已于 2026-10-01 更正 —— 原记载的两条前提均已失效, 勿照此排查**:

| 原记载 | 实际情况 |
| --- | --- |
| `GRAALVM_VERSION=25.0.1` 资产被回收, job 在 `setup` 阶段 17 秒内红 | **已于 `7c095a3` 修复并推送**, 改为 `GRAALVM_DISTRIBUTION: graalvm-community` 由 action 动态解析 |
| master 最近 12 次 Pipeline 全部 failure, 自 2026-09-05 起从未真正跑过 | 修复后 CI **确实编译并测试了**: 2026-09-30 07:17–07:44 的运行耗时 4–11 分钟, 跑出了真实的 IT 失败 |

**当前的真实阻塞不是代码**: 2026-09-30 08:13 起所有 job 在 3–9 秒内即红, 原因是
`The job was not started because recent account payments have failed or your spending
limit needs to be increased` —— **账号计费/额度问题**, 任何代码改动都修不了,
需人工在 GitHub `Settings → Billing & plans` 处理。
2026-10-01 复核仍在同一状态 (`gh run view 36697715625`)。

> **【2026-10-01 08:00 再更正】账号计费已恢复, CI 确实在跑**
>
> 上述“阻塞”只持续到 2026-10-01 上午。推送后的 run `36832793467` / `36836223793`
> **真正执行了 10 分钟**并报出真实失败, 此后不再复现阻塞。
>
> **CI 首次真正跑起来后查出的真实问题**（均为长期掩盖，**非本次改动引入**）:
>
> | 问题 | 归属 | 状态 |
> | :--- | :--- | :--- |
> | `NotificationDispatcher.decodeChannels` 命中 PMD `ReturnEmptyCollectionRatherThanNull` | 008d773（前次遗留） | 已修 `ca1eac8` |
> | ArchUnit 步骤的 `-DfailIfNoTests` 在 Surefire 3.x 已失效 → **该门禁从未真正执行过架构校验** | CI 配置长期缺陷 | 已修 `22ebb8e` |
> | IT 在 CI 上失败（notification 7 + gateway 4 项） | **测试抹除 failsafe 注入的密钥属性** | 已修 `b2534a1` |
>
> **【2026-10-01 定性】IT 失败的真根因不是 CI 环境, 我先前的归因是错的**
>
> 我最初按日志里的 `UnknownHostException: cloud-nacos` 判为“runner 环境差异”，
> **那是干扰项**。下载 CI 上传的 `test-reports` artifact、逐层读 `Caused by` 链才得到真实根因：
>
> ```
> Unable to start web server
>   → Unable to start embedded Tomcat
>     → Error creating bean 'webSocketAuthFilter'
>       → Error creating bean 'internalCallSecretProvider': init failed
>         → IllegalStateException: 内部调用签名凭据缺失
> ```
>
> `WebSocketIdentityTrustIT` 与 `SystemGrpcSecurityBoundaryTest` 的 `@AfterEach` 用
> `System.clearProperty("cloud-infra.internal-call.secret")` 清理，**顺手抹掉了 pom.xml
> `systemPropertyVariables` 在 JVM 启动时注入的同一个属性**且不恢复；而
> `InternalCallSecretProvider` 是 fail-fast 的（issue 30 之后无默认值），
> 于是同 JVM 内后续需要它的 IT 全部启动失败。
>
> **本地不复现仅因测试类执行顺序不同**（order-dependent test）。用
> `-Dfailsafe.runOrder=reversealphabetical` 强制污染源先跑即可确定性复现：
> 修复前 `NotificationMqFlowIntegrationTest` 5 ERROR + `NotificationServiceIntegrationTest` 7 ERROR，
> 修复后同一命令 **30 项全绿**。
>
> **教训（已记入第 4 节测试夹具隔离）**: `@AfterEach` 清理系统属性必须**还原原值**，
> 不能无条件 `clear` —— 构建插件注入的值不属于“本测试设置的”。本次共发现 4 处：
> 2 处既有 + 2 处本次新写的。
>
> **值得记下的**: 本次推送的三个新 IT 在 CI 上**全部通过**
> （`TenantIsolationEndToEndIT` 4、`GatewayTenantPropagationIT` 7、
> `ScheduledTaskRuntimeTriggerIT` 2），单测也通过 —— 即本次改动本身在 CI 上成立。

详见 `ROADMAP.md` “运行时有效性审计” 与 issue 29。

---

## 🔒 第四课: 信任边界必须逐跳检查 (2026-10-01 新增, 强制)

**背景**: 修多租户“部署态无效”时（commit `adefc3b`），除已知的“开关没开”外，
**又查出两处此前无人记录的越权路径**。两者都不是“代码写错了”，而是**信任边界画在了错误的位置**。

### 教训 1: 修复信任链时, 要检查链上的每一跳, 不是最显眼的那一跳

多租户的真实链路里, 网关侧看到的是“身份验签了, 就该信任租户”, 但下游是两条独立的过滤器:

| 过滤器 | order | 对 `X-User-Id` | 对 `X-Tenant-Id` |
| :--- | :--- | :--- | :--- |
| `TenantContextFilter` | **-110** | 不读 | **无条件信任** |
| `HeaderAuthenticationFilter` | 默认 | **验签** | 不读 |

于是身份验签了、租户是敞开的: 绕过网关直连业务服务, 指定任意 `X-Tenant-Id` 即可读他租户数据。
**只要一个系统有多个同类“信任型”头, 每个都必须单独确认验签**——
因为“先执行的那个过滤器”往往正是没设防的那个。

**开工前必答**: 这个新信任的头, 和已有被保护的头, 是**同一套校验**吗?
不是的话, 为什么不是?

### 教训 2: `catch (Exception)` 能不能收窄, 取决于它是**哪种边界**

本次两处都触发 ADG 门禁 `Catching generic Exception hides specific errors`, 但语义**相反**:

| 位置 | 边界类型 | 正确做法 | 收窄的后果 |
| :--- | :--- | :--- | :--- |
| `AuthEvaluator.evaluate` | **安全判定** | 保留宽捕获 → fail-closed 成 401 | 内部缺陷(NPE)逃出去变 500, 把“拒绝”伪装成“服务故障” |
| `AuthTokenServiceImpl.delToken` | **错误规范化** | 保留宽捕获 → 包成 `TOKEN_INVALID` | 漏掉的异常变 500, 违反既有契约(`AuthTokenServiceTest` 捕获) |

**不要一律照门禁改。** 先问: 这个 catch 的目的是“拒绝”还是“归一化”?
前者宽捕获是安全属性, 后者宽捕获是契约属性。门禁只能看到语法, 看不到意图——
在注释里写清理由, 是让下一个人 (和下一次的自己) 免于误改的唯一办法。

### 教训 3: 守护测试的有效性, 只有负向对照能证明

新增 12 项单测 + 4 项端到端 IT 之后, **把验签条件改成 `if (... && false)`, 3 项单测如期失败**。
没做这一步的话, 无法区分“测试真的在守护”与“测试恰好通过”——本次历史上已出现过
11 项补扫测试全绿而缺陷仍在的案例 (第三条硬规则)。

**新交付一项能力的最低验证动作**: 改坏它, 确认守护会红, 改回来, 确认会绿。

> **同日第二次复现, 而且这次抓到的是我自己刚写的守护。**
> 为“密钥拿不到 → 全站 403”补的 `TenantFilterSecretWiringTest` 里, 我写了
> `p.startsWith("com.cloudinfra")` 作为“扫描范围覆盖密钥类”的判据。
> 负向对照把 `basePackages` 收窄为 `com.cloudinfra.system` 后 —— **守护没变红**:
> 该串确实以 `com.cloudinfra` 开头, 却不包含密钥所在的 `com.cloudinfra.common`。
> 改为精确包含关系 (包相等, 或以 `包 + "."` 为前缀) 后才真正变红。
>
> 结论比原话更强: **“我加了守护”不等于“守护有效”, 而差的那一步恰好就是负向对照**。
> 它守护的是全站 403 级风险, 假绿尤其致命。

### ⚠️ 第五课补记: 守护自己的失败也会掩盖真实问题（2026-10-02）

排查 notification 的 `@Qualifier` 缺陷时，我写了字节码守护并挂在 E2E 的**早期步骤**。
它需要 `target/classes` 里的字节码，那要等编译完成 —— 于是：

```text
mvn -o → Non-resolvable import POM（既无依赖也无字节码）→ 该步红 → 整个 run 中断
```

代价是 E2E 主脚本根本没跑，**更早的真实失败被换成了一个看起来合理的红灯**。

两个层面的错误：

1. **位置错误**：需要编译产物的守护，只能放在编译之后。
   放在之前，它要么误报，要么不得不写成“缺产物就跳过” —— 而跳过若无条件执行，
   就成了“只有全绿才检查”的假门禁（比没有更坏）。
   故正解是两件事同时做：**移到编译后** + `if: always()` 保留失败时的可执行性。
2. **跳过 ≠ 通过**：无字节码时必须 `SKIP` 并**打印原因**，`exit 0` 属于“本次不判定”，
   绝不能 `exit 1`（那是把“没测”说成“测了不过”），也绝不能静默 `exit 0`。

> 这条与第四课的“负向对照”同源：守护的价值不在“它会红”，而在“它红的时候确实有东西坏了”。
> 守护自己出错时，它和被守护对象一样会骗人。

### 证据分级（本次实践后的补充）

| 级别 | 手段 | 能证明什么 |
| :--- | :--- | :--- |
| L1 结构性 | 读源码 / 反射扫描 / 自动配置登记 | 机制存在 |
| L2 触发性 | 守护测试断言装配与默认值 | 条件成立时代码会执行 |
| L3 端到端 | **真实容器**跑完整链路 | 运行时确实按预期工作 |
| L2.5 负向对照 | 改坏 → 断言守护变红 | 守护本身有效 |

### ⚠️ 第五课: **看起来正常的错误信息**比报错更危险 (2026-10-03 新增)

排查 Nacos 配置与 ES 400 时反复撞上一种新形态, 与前三课都不同:
**取证手段本身失效, 但不报错, 而是给出一个语法合法、语义错误的结论。**

三个实例, 全部由我自己造成:

| 实例 | 探针写的对象 | 实际对象 | 探针输出 | 真实含义 |
| :--- | :--- | :--- | :--- | :--- |
| ES 诊断 v1 | `oper_log` | `sys_oper_log` | `404` | 索引名不存在, 与 ES 是否健康无关 |
| ES 诊断 v2 | `device_telemetry`（按**类名**猜） | `iot_telemetry` | `404` | 同上 |
| 回读守卫 v1 | 先剥离字符串字面值 | 需匹配 URL 与 `$file` | “找不到回读点” | 把要守的东西也擦了, 恒假红 |

**为什么这类比报错难发现**:

- 报错会中断流程; 假结论会**继续往下走**, 并在后续推理中被当成事实。
- 本项目已被“机制存在 ≠ 机制在运行”坑过 (第三课), 但那至少还会红。
  这一类的特征是**全绿**: 测试通过、日志有输出、结论自洽 —— 只是结论错了。
- 第二例尤其典型: 我从 `DeviceTelemetryDocument` **推导**出 `device_telemetry`,
  推导链本身合理, 但真实索引名是 `iot_telemetry`。
  **类名/文件名/资源名与运行时标识符之间没有必然对应关系**,
  靠命名直觉推断必错。

**三条可直接执行的规则**:

1. **探针的对象必须从被测系统里提取, 不能手写**。
   正确做法: 从 Java 侧提取全部 `@Document(indexName=...)`,
   与诊断脚本的列表**双向比对**（缺失和多余都判红）——
   `deploy/tests/test_es_diagnostics_index_names.py` 即此例。
2. **“探不到”有两个解释, 必须区分**: 对象不存在(探错了) vs 对象存在但不可用(真故障)。
   这正是 `HEAD /<index> -> 404` 无法单独作为证据的原因:
   它同时支持两种互斥结论。
3. **静默降级的取证比没有取证更坏**。宁可让探针在“对象不确定”时直接失败,
   也不要让它继续输出一个格式正确的结果。

**与 L2.5 的关系**: 负向对照仍能救这一类 —— 但前提是**对照要打在探针本身**。
我第一版索引名守护的提取正则用了 `[^\s;]+`, 对 `for idx in a b c; do`
只抓到第一个索引, 于是漏掉的恰好是“未被探测”的那部分 ——
即最需要被发现的那部分。对真实脚本跑正向时它立刻报红, 才暴露出来。
**若当时是拿桩数据跑正向, 这个洞会一路留到 CI。**

`TenantIsolationEndToEndIT` 的定位: 真实 PostgreSQL 容器 + 真实 `TenantSqlInterceptor` + 真实过滤器,
而非 mock 直调方法。

**但它不是本项目第一个 L3 多租户证据** —— `docs/perf/2026-09-04-m6-01-runtime-verify.md`
已有一份人工运行时验证报告。它的增量价值在于**自动化与可重复**:
那份报告是一次性的人工取证, 不在默认构建里跑, 回归时不会自动重现;
本 IT 每次 `mvn verify -DskipITs=false` 都会执行, 因此能长期充当回归网。

---

## 🚫 框架能力准入原则 (2026-09-29 新增, 强制)

本项目已出现约 4200 行“已实现且有自测, 但业务零引用”的框架代码。新增框架能力前必须先回答:

1. **先有第二个真实用例, 再有抽象。** 只有一个用例时直接写具体代码。
2. **提出来源必须是业务, 不是设想。** 说清“哪个现有业务场景现在就需要它”。
3. **判定完成必须有业务模块引用 + 运行时证据。** 组件自身单元测试只证明它能自我运转,
   **不得作为完成依据**。
4. **抽象层数不超过必要**: 每个 `interface` 要么已有 2 个以上实现, 要么能说清 6 个月内
   会出现第二个实现。
5. **不为了“看起来完整”而补齐配套**: 没有使用方的聚合脚本、导出器、面板一律不建。

违反上述任一条即视为过度设计。详见 `.scratch/2026-q3-roadmap/issues/27-framework-admission-and-cuts.md`。

> **当前存量提醒** (2026-10-01 更新): 约 4200 行零业务引用代码 (编排引擎 764 + Outbox 767 +
> 幂等 500 + codegen 2464)。保留但暂停投入。
> **Outbox 的业务负担已于 issue 29 解除**: opt-in 失效已修,
> `deploy/config/search-service.yaml` 里那个“并不生效”的规避开关与对应死配置已删除。

---

## 🛠️ 开发者快速指南 (Developer Operations)

### 1. 核心构建指令
- **全量构建 (跳过测试)**: `mvn clean install -DskipTests`
- **单模块构建**: `mvn clean package -pl <module-name> -am -DskipTests`
- **Native 镜像构建**: `./build-native.sh` (内存建议 12GB+，仅针对 Gateway 和 Auth)
- **Kubernetes 部署**: `./build-k8s.sh` (构建镜像并加载至 Kind 集群)

### 2. 环境启动与验证
- **快速启动本地环境**: `./deploy/up.sh` 或 `./build-local.sh`
  - 依赖 `podman-compose` (非 docker-compose)。
  - 流程：清理旧环境 $\rightarrow$ 启动基础设施 (Nacos, Redis, etc.) $\rightarrow$ 同步配置到 Nacos $\rightarrow$ 启动微服务。
  - 首次运行会自动生成 `.env` (含随机 JWT 密钥)，密钥缺失会导致服务拒绝启动。
- **基础设施清理**: `podman-compose -f deploy/compose.yaml down`
- **验证端点**:
  - 网关: `http://localhost:9000`
  - Nacos: `http://localhost:8848/nacos`
  - Prometheus: `http://localhost:9090`

### 3. 测试执行
- **执行模块所有测试**: `mvn test -pl <module-name>`
- **执行单个测试类**: `mvn test -pl <module-name> -Dtest=<TestClassName>`
- **测试底座**: `cloud-infra-test-support` 提供基于 Testcontainers 的集成测试能力 (Postgres, RabbitMQ, ES)。

---

## 🏗️ 系统架构分析 (System Architecture)

### 1. 整体设计
Cloud-Infra 是一个基于 **JDK 25 (虚拟线程)** 和 **Spring Boot 4.1.0** 构建的高并发微服务平台。

- **极致性能**: 全面启用 `spring.threads.virtual.enabled=true`，配合 **Generational ZGC** 消除线程阻塞瓶颈，保持微秒级 GC 停顿。
- **统一入口**: `cloud-infra-gateway` 处理全局认证、限流和路由，将身份信息 (`X-User-Id`, `X-User-Name`) 注入下游。
- **身份模型**: `cloud-infra-auth` 负责 JWT 签发与刷新，下游通过 `framework-security` 执行 RBAC 鉴权 (`@ss.hasPermi`)。

### 2. 模块职责地图
| 模块 | 职责 | 注意事项 |
| :--- | :--- | :--- |
| `cloud-infra-api` | Feign 客户端及共享 DTO | **禁止** 编写业务逻辑 |
| `cloud-infra-common` | 通用工具类、基类、全局异常 | 仅存放无状态工具代码，**已移除 spring-cloud-stream 依赖** |
| `cloud-infra-framework` | 内部 SDK (MyBatis, Redis, Security, Tracing) | 封装底层技术能力，`framework-security` 对 `api` 依赖 `optional` + `@ConditionalOnClass` |
| `cloud-infra-starter` | 引导与自动配置组件 | 负责框架能力的自动装配 (租户、平台属性) |
| `cloud-infra-gateway` | 统一网关 | 负责身份传播、路由拦截、内部调用签名剥离 |
| `cloud-infra-auth` | 认证中心 | 负责 Token 签发、登录锁定、验证码校验、JWT 黑名单 (logout jti) |
| `cloud-infra-modules` | 核心业务微服务 (`system`, `file`, `notification`, `search`) | 业务逻辑实现层。**鉴权覆盖由 `ControllerAuthorizationTest` 守护** (2026-09-30 审计发现 3 个端点"认证即可读", 已修; 非 `/internal/` 端点必须有 `@PreAuthorize`, 例外清单必须保持最小)。**多租户已在部署态开启** (`deploy/config` 三个服务), 租户来自 JWT claim 而非客户端请求头, 详见下方“第四课” |
| `cloud-infra-codegen` | 代码生成框架 (`⚪` 可选工具, 非路线图能力) | 含 `core` (JDK 17 编译) + `maven-plugin` (JDK 17, 自包含) 子模块; 仅 `sys_post` 一表验证过, **停止新增模板** |
| `cloud-infra-test-support`| 集成测试底座 | 提供 Testcontainers 配置 |

### 3. 核心业务流程
- **身份传播**: `客户端` $\rightarrow$ `Gateway (校验 JWT $\rightarrow$ 注入 Header)` $\rightarrow$ `下游服务 (HeaderAuthenticationFilter $\rightarrow$ SecurityContext)` $\rightarrow$ `@PreAuthorize` 校验。
- **配置管理**: 本地 `deploy/config/*.yaml` $\rightarrow$ `deploy/up.sh` (同步) $\rightarrow$ `Nacos` $\rightarrow$ `微服务运行时加载`。
- **异步处理**: 通过 `VirtualThreadConfig` 定义的虚拟线程执行器，确保 `@Async` 操作零池化开销。
- **消息消费**: 直接 `@RabbitListener` (concurrency 3-10) + Redis 幂等去重 (TTL 24h) + DLQ 重试。

---

## ⚠️ 代理注意事项 (Agent-Specific Guidance)

### 1. 技术栈陷阱
- **JDK 25 特性**: 优先使用虚拟线程。在编写并发代码时，避免使用 `synchronized` 块（可能导致虚拟线程钉住/pinning），建议使用 `ReentrantLock`。
- **Podman-Compose**: 本项目使用 `podman-compose` 而非 `docker-compose`，指令基本兼容但环境依赖不同。
- **Native Image**: 构建 Native 镜像需激活 `-Pnative` profile。

### 2. 开发约定
- **逻辑分层**: 业务逻辑必须在 `cloud-infra-modules` 的 `service` 层实现。
- **API 更新**: 修改接口时，必须同步更新 `cloud-infra-api` 中的 Feign 接口和 DTO，以确保跨服务类型安全。
- **配置修改**: 修改服务配置应优先修改 `deploy/config/` 下对应的 `.yaml` 文件，然后运行 `./deploy/up.sh` 同步至 Nacos。
- **密钥管理**: 所有密钥 (JWT, INTERNAL_CALL, DB, MQ, MinIO) **禁止默认值**，必须通过环境变量注入。`compose.yaml` 使用 `${VAR:?}` 强制模式，`up.sh` 自动生成 `.env`。

  > **【2026-10-01 新增】`INTERNAL_CALL_SECRET` 只认两种注入方式, 其余一律无效**
  >
  > `InternalCallCrypto.resolveSecret()` 刻意绕开 Spring `Environment`
  > （为规避 jasypt 4.0.4 + Nacos `CachingResolver` 的时序冲突）, 因此它**只读**:
  > 1. 环境变量 `INTERNAL_CALL_SECRET`
  > 2. JVM 系统属性 `-Dcloud-infra.internal-call.secret=...`
  >
  > **以下方式写了不生效, 且因缺失即 fail-fast 而导致服务启动失败**:
  > - `--cloud-infra.internal-call.secret=...` 命令行参数
  >   （Spring Boot 3+ 只放进自己的 Environment, 不再写回 `System.getProperty`）
  > - `application.yml` / `deploy/config/*.yaml` 里的同名键
  > - Nacos 配置中心的同名键
  >
  > 2026-10-01 实际踩到：`CrossServiceFeignChainIntegrationTest` 用 `--key=value` 给子进程
  > 注入密钥, 导致 system 子进程 `internalCallSecretProvider` 初始化失败、启动即退,
  > 该 IT 稳定失败（也正是 2026-09-30 CI 报出的 gateway IT 失败之一）。
  > 改用 `-D` 后该 IT 从 1 项 ERROR 变为 **5 项全绿**。

### 3. 关键依赖版本
- **Spring Boot**: 4.1.0
- **Spring Cloud**: 2025.1.2
- **JDK**: 25
- **数据库**: PostgreSQL 16 (实际使用) / MySQL 8.4 (驱动可配)
- **中间件**: Redis 7.2, Nacos 3.x, Elasticsearch 8.18, RabbitMQ 3.x
- **可观测性**: OpenTelemetry (OTLP 4318), Prometheus, Zipkin, Grafana

### 4. 测试夹具隔离 (2026-10-01 新增)

**2026-10-01 实际踩到的事故**: 新写的 `ScheduledTaskRuntimeTriggerIT` 放在 `com.cloudinfra.system` 包下,
其静态嵌套夹具 `@Configuration TaskHarness` 注册了一个 `SysTxMessageService` 的 **mock**。
`SystemApplication` 的 `@ComponentScan(basePackages = "com.cloudinfra")` 会扫到它 ——
`ClassPathScanningCandidateComponentProvider` 默认 `considerIndependentNestedClasses = true`,
**静态嵌套的 `@Configuration` 会被发现**。

后果: 那些夹具在**其他测试的 Spring 上下文里也生效**, 真实 service 被 mock 替身覆盖,
令 `TxMessageFlowIntegrationTest` 的 `TxMessageRetryTask` 拿到空列表而不真正查库 → 稳定失败。

**为什么难发现**: 单独跑被污染的测试永远是绿的; 只有整套 IT 一起跑才显形。
用 `git stash` 做基线对照是定位这类问题的可靠手段。

**规则**: 需要注册 bean 的测试夹具, 放在 **`com.cloudinfra` 之外的包**（本次用 `com.cloudinfrait.*`）,
或确保它不带 `@Configuration` / `@Component`。已随本次修正迁移的夹具:
`ScheduledTaskRuntimeTriggerIT`、`TenantFilterWiringTest`、`IdempotentOptInTest`。

**存量提醒**: 全仓还有 8 个同模式夹具（notification 4 个、file 1 个、framework-web / tracing / mybatis 各 1 个）。
2026-10-01 首次完整跑完全量 IT（23/23 模块、111 项）已通过，说明它们之间暂未构成实际冲突。
但**新增夹具时必须遵守上面的规则**，否则下一个被扫到的就是它。
逐个迁移属存量清理，在出现实际冲突前不做（见准入原则）。

### 5. 系统属性清理必须还原, 不能无条件 clear (2026-10-01)

**与上节同源的另一类污染**: 测试改的是 **JVM 全局系统属性**。

`pom.xml` 的 surefire / failsafe 都配了
`<systemPropertyVariables><cloud-infra.internal-call.secret>${test.internal-call.secret}</...>`,
即构建插件在 **JVM 启动时**注入该属性。而若某个测试的 `@AfterEach` 写
`System.clearProperty("cloud-infra.internal-call.secret")`,
它抹掉的是**构建插件注入的值**, 不只是自己设的 —— 同 JVM 内后续需要
`InternalCallSecretProvider` 的测试会拿到 `null`, 而该 bean 是 **fail-fast** 的
（issue 30 之后无内置默认值），于是**整类 IT 连带启动失败**:

```
Unable to start web server → Unable to start embedded Tomcat
  → Error creating bean 'internalCallSecretProvider': init failed
    → IllegalStateException: 内部调用签名凭据缺失
```

**这类失败极难归因**: 本地几乎必然绿（测试类执行顺序不同），
只在特定顺序下才红; CI 上的报错还夹杂 Nacos 之类的干扰信息。
本次我正是先被 `UnknownHostException: cloud-nacos` 带偏,
下载 `test-reports` artifact 逐层读 `Caused by` 链才找到真因。

**规则**: 改全局系统属性必须"存原值 → 还原"：

```java
@BeforeEach void remember() { original = System.getProperty(KEY); /* 改 */ }
@AfterEach  void restore() {
    if (original == null) System.clearProperty(KEY); else System.setProperty(KEY, original);
}
```

**确定性复现手法**（把偶发变必然）:
`mvn failsafe:verify -Dfailsafe.runOrder=reversealphabetical` —— 强制让污染源先跑。
本次据此在本地复现了 CI 的失败, 修复后同一命令 30 项全绿。

共发现 4 处: `WebSocketIdentityTrustIT`、`SystemGrpcSecurityBoundaryTest`（既有）
+ `GatewayTenantPropagationIT`、`TenantFilterWiringTest`（本次新写, 同一错误）。

**CI 验证（run `36845519152`）**: notification-service 的 IT 从 12 项 ERROR
（`NotificationServiceIntegrationTest` 7 + `NotificationMqFlowIntegrationTest` 5）
降到 **0** —— 修复生效。

**仍存两项, 均非本次引入**:

1. `WebSocketIntegrationTest` 偶发 1 项 ERROR:
   `The remote endpoint was in state [TEXT_FULL_WRITING]`。
   **已定性并修复**（commit `91c8f71`）：`getBasicRemote()` 是阻塞式非线程安全端点，
   心跳 pong（Tomcat 消息线程）与业务推送（MQ 消费线程）会并发进入同一 session。
   现以 session 自身作锁把**每连接**的发送串行化（锁粒度不是全局，不影响跨连接并发），
   并新增 `NotificationWebSocketSendConcurrencyTest` —— 用模拟 Tomcat 状态机的假端点
   把并发窗口固定下来，含负向对照。
2. ~~`CrossServiceFeignChainIntegrationTest` 4 项失败~~ → **已修复**
   （`fae998c` + `4ba43bd`），CI run `36883836629` 验证 **5 项全绿**。

   定位过程值得记录 —— **症状与根因相距四层**，靠读业务代码几乎推不出来：
   1. 表面症状：登录返回"用户名或密码错误"，看似密码/数据问题
   2. 逐一排除：验证码(`CAPTCHA_ERROR`)、登录锁定(`ACCOUNT_LOCKED`)、密钥属性(`b2534a1`)
   3. **直探端点给出转折**：直连 system 返回 **HTTP 200 + 完整 admin 数据**（BCrypt hash + 40 权限），
      而登录返回的是 **Spring Boot 默认 500 格式**（`{"timestamp","status","error","path"}`）
      而非 auth 的统一 `Result` 格式 → 排除 Feign/签名/数据，指向"网关转发时连不上 auth"
   4. 网关日志坐实：`SecurityFilter - Security filter error: ResourceAccessException`
   5. **根因一**（`fae998c`）：gRPC 默认启用（`matchIfMissing=true`）且**固定端口 9090**，
      CI 上该端口被占 → `BindException` → system 子进程启动即失败
   6. **根因二**（`4ba43bd`）：`SimpleDiscoveryProperties` 是 `@ConfigurationProperties`，
      在 bean 初始化时**一次性绑定**；而 JUnit 5 里 `BeforeAllCallback`（Spring 上下文加载）
      **先于** `@BeforeAll`（startServices）→ 绑定时 `authPort` 还是 0 → 网关转发到 `127.0.0.1:0`。
      原注释"supplier 惰性求值——上下文在 @BeforeAll 之后加载"这个假设**恰好是反的**，
      而且 Supplier 也救不了（它只在属性被读取那一刻求值，绑定只发生一次）。
      修复：子进程与网关配置统一用**固定端口**（18081 / 18082），从根上消除时序依赖。

   附带的诊断能力（`7c44680`）：`probeSystemCredentialEndpoint()` 直连 system 内部端点，
   能把"登录失败"拆成连接异常/401/查无此人/数据正常四类。第 3 步的转折正是它给出的。

### ⚠️ Native Image Build：gateway 已通过；auth 的失败经对照实验确定与本次改动无关

`Native Image Build (Gateway + Auth)` 此前因上游 job 失败一直被 **skipped**，
2026-10-01 才第一次真跑起来，失败于 `native-image [8/8] Creating image...`。

**gateway 已解决。** 四档内存配置的实测结果：

| 配置 | gateway | auth |
| :--- | :--- | :--- |
| `-J-Xmx8g`（原，不限 MaxRAM） | 失败 | 失败 |
| `-J-Xmx4g`（不限 MaxRAM） | 失败 | — |
| `-J-Xmx4g -XX:MaxRAM=10g` | ✅ 成功（9m9s / Peak RSS 6.18GB） | 失败 |
| `-J-Xmx4g -XX:MaxRAM=14g`（**当前**） | ✅ | 失败 |

即：**必须限制 MaxRAM**（放开后 image heap 会自行涨到顶穿 16 GB runner），
堆 4 GB + MaxRAM 限制即可让 gateway 稳定通过。

> ⚠️ auth 那一列的"失败"**不能读作内存不足** —— 详见下方自我更正：
> 它在 5 秒内就失败了，压根没进入 native-image 构建阶段。

**auth 的失败已由 A/B 对照实验定性为既有问题，与本次改动无关。**
2026-10-02 开 PR #58，仅回退 auth 的两个生产文件
（`AuthTokenServiceImpl` / `UserServiceImpl`）到 `0c66985`，其余不动 —

| | gateway | auth |
| :--- | :--- | :--- |
| master（含本次 auth 租户改动） | 成功（9m9s） | 失败，5.188s，插件启动阶段 NPE |
| PR #58（回退 auth 租户改动） | 成功 | **失败于同一模块、同一阶段、同一 NPE** |

回退后 auth 以**完全相同的方式**失败、gateway 同样成功，
故 auth 的 native 失败属**既有问题**（该 job 长期 skipped、从未真正验证过），
**不作为本次改动的遗留问题记账**。

### ⚠️ 对上述“同一种失败”的自我更正（2026-10-02 深夜复查）

我先前把 auth 的失败描述为“停在 `[8/8] Creating image` 之后、被系统终止、无错误输出”，
**这个描述是错的** —— `[8/8] Creating image` 那几行属于**刚刚成功完成的 gateway**，
它紧跟在 gateway 的 `Finished generating ... in 9m 9s` 之后、Reactor Summary 之前。
我把相邻模块的输出当成了 auth 的失败现场。

取完整错误行后，auth 的真实失败是:

```
[INFO] cloud-infra-auth .................. FAILURE [  5.188 s]     ← 仅 5 秒
[ERROR] Failed to execute goal org.graalvm.buildtools:native-maven-plugin:0.10.4:compile
        (default-cli) on project cloud-infra-auth: Execution default-cli of goal
        ...:compile failed: Cannot invoke "java.util.LinkedHashMap.get(Object)"
        because "this.inodes" is null
```

**5.188 秒**意味着 **native-image 进程根本没被启动** ——
所以它既不是“分析失败”，也不是“内存耗尽”“被系统终止”。我先前那套
“四档内存配置都失败 ⇒ 内存参数不是关键变量”的推理**前提就是错的**：
既然没跑到构建阶段，内存参数当然不可能是原因，那四档实测其实**没有区分力**。

### ✅ Native 构建已全部通过（CI run 36969220950）

改为**两个模块分两次 Maven 调用**后，native 门禁首次全绿。证据不止job 状态：

- `Upload native binaries` 日志明确：`there will be **2 files** uploaded`，
  路径正是 `cloud-infra-gateway/target/cloud-infra-gateway-binary` 与
  `cloud-infra-auth/target/cloud-infra-auth-binary` ⇒ **auth 的二进制确实生成了**
- artifact `native-binaries` 最终大小 100,735,406 字节
- 即失败确由“gateway 成功后的同一 Maven 会话内构建 auth”触发，
  分开会话即消除

> 教训：当时有两条**本机 mmap 崩溃**的失败日志（`nat_head.log` / `nat_auth_only.log`），
> 很容易让人认定“本机也无法验证、只能靠 CI”，从而放弃用本机做对照实验。
> 实际上 auth **单独**构建时能跑过插件并真正启动 native-image ——
> 这条对照才是定位到真因的关键。**不要因为一个环境跑不通就停止用它做对照。**

### ⚠️ 又一个被依赖链掩盖的既有缺陷：Docker 构建

native 修好后，`Build & Push Docker Images` 首次真正执行，立刻失败：

```
##[error]Unable to resolve action redhat-actions/podman-installer, repository not found
```

经 GitHub API 核实：该 action 已被**重命名**为 `redhat-actions/podman-install`
（`podman-installer` 返回 404，`podman-install` 存在且有 v1/v1.1.x）。
同 job 其余引用经核实均有效（`actions/download-artifact@v8`、`redhat-actions/podman-login@v2`）。

**这个缺陷同样从未暴露过**：本 job `needs` native-build，而 native-build
在 2026-10-01 之前长期 skipped，于是它也一直 skipped。
“上游坏掉 → 自己伪装成从未验证过”，这是本项目第 6 例同类问题。

### 6. 质量基线 (2026-10-01)
- **构建**: `mvn -o clean test` → BUILD SUCCESS, **501 项单测, 0 失败, 1 跳过** (2026-10-01 实测, 以 surefire XML 为准, 勿用控制台汇总行累加 —— 会重复计数)。**注意: 单测数不是交付证据** —— 见上方第三条硬规则, 2026-09-30 审计查出 4 处"自测全绿但实际从未运行"的缺陷
- **集成测试**: `skipITs` 默认 `true`，`mvn test` 跑不到 IT。手工跑全量:
  `nohup mvn -o verify -DskipITs=false -DskipUnitTests=true > /tmp/fullit.log 2>&1 &`
  —— **必须用 nohup 或分模块跑**: 全部 23 个模块的 Reactor 累计耗时 **37 分 13 秒**
  （含编译与打包，2026-10-01 实测），远超前台 600s 上限（本次连续被截断 **2 次**）。
  2026-10-01 实测：**23/23 模块 SUCCESS，26 个 IT 类 / 111 项，0 失败**，
  这是本项目**首次完整跑完全量 IT**（此前因超时长期只有部分覆盖）。
  ⚠️ **分模块跑（`-pl <module>`）会掩盖跨模块的夹具污染**（见上节）—— 验证时优先用全量。
  CI 侧 IT 步骤本身是好的（`GRAALVM` 问题已于 `7c095a3` 修复并推送），但**账号计费自 2026-09-30 08:13 起阻塞所有 job 启动**，详见上方“CI 诊断更正”。计费恢复后需先处理它跑出的真实失败：2026-09-30 07:44 那次运行报出 notification / file / gateway / search 四个模块的 IT 失败
- **安全**: 管理端点收敛 (9301/management), 异常脱敏, JWT 黑名单 (Redis TTL 24h, JwtAuth + HeaderAuth + gateway SessionStore 三层), 文件魔数校验, P0-3b HeaderAuth blacklist 修复 (commit 370dcd1+ae06566)。WebSocket 信任模型已与 HTTP 对齐 (`verify-internal-sign` 同一开关, 2026-09-30); **该开关已改为默认 fail-closed (true), 两侧同默认, commit `2c4e528`** —— 此前默认 false 使 `isTrustedInternalRequest` 首行恒真、验签代码不可达。守护测试 `*DefaultFailClosedTest` x2 走真实 Spring 装配断言默认值, `WebSocketIdentityTrustIT` 提供端到端证据
- **X-Trace-Id 校验**: 外部可控的 `X-Trace-Id` 写入 MDC 与响应头前先按 `^[A-Za-z0-9_-]{1,64}$` 校验, 阻断日志伪造 (2026-09-29)
- **gRPC 安全边界** (M7-02 Phase D): 内部调用 HMAC 签名 + 调用方身份 + 租户上下文三道校验, 失败统一 `UNAUTHENTICATED` 且不区分原因; `hasPermi` 在 gRPC 通道内恒为 false, 写操作仍禁止放开
- **架构**: framework-security optional api, common 无 stream 依赖, gateway 路由修复 (IdentityRequestWrapper.getHeaders + @LoadBalanced RestClient), **cloud-infra-codegen 模块新增** (core + maven-plugin 双子模块)
- **性能**: KEYS→SCAN, N+1 批量删除, 分页 LIMIT, Feign 显式超时, ZGC+512m, 基准测试 (10并发 146 RPS, p99 165ms — 2026-08-31 实测, 较旧基线 57 RPS / 1066ms 提升 2.5-6x)
- **事务**: 14+ @Transactional rollbackFor, Feign 调用移出事务, DLQ 重抛
- **部署**: compose restart/healthcheck, Helm PDB, prometheus port 9301, Seata disabled
- **代码生成**: `cloud-infra-codegen` (`⚪` 可选工具) 含 10+ 模板, 支持 Maven 插件/CLI/编程式三种调用, 基于 Flyway DDL 解析。**不再投入**

## Agent skills

### Issue tracker

Issues live as files under `.scratch/` in this repo. See `docs/agents/issue-tracker.md`.

### Triage labels

Standard triage labels are used. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout. See `docs/agents/domain.md`.
