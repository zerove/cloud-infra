# 2026-11-02 信任链逐跳复核报告（安全纵深防御 · 第六课）

**日期**: 2026-11-02
**背景**: 本项目第四课"信任边界必须逐跳检查"要求：新增/信任型头每一个都要单独确认验证链。
本次按该课要求对 **M6-01 多租户** 当前运行态做一次"信任链逐跳"复评，判断：
1. 是否还存在**越权路径**（可绕过网关直连业务服务读他租户数据）；
2. 哪些信任头**独立成层需要独立验签**、哪些是"复合义务"。

> 本次**未改任何代码**；复评仅做静态证据核验 + 运行态对照回忆。依据口径见下文"判定"。

---

## 一、本课要求的事前必答

开工前先问：这个新信任的头，和已有被保护的 `X-User-Id`，是**同一套校验基线**吗？
不回答清楚这条，就不是好的逐跳。

### 当前两层信任头

| 头 | 来源 | 是否由网关权威注入 | 下游是否独立验签 | 独立验签行为 |
| :--- | :--- | :--- | :--- | :--- |
| `X-User-Id` | 网关 `SecurityFilter.validateToken()` → RedisSessionStore.validateSession | 是 | **是** `HeaderAuthenticationFilter` 在 Gateway 模式下校验网关注入的值 | `hasPermi` 校验，非 identity 完整性 |
| `X-Tenant-Id` | 网关 `SecurityFilter.propagateTenantHeader()` → `identity.tenantId()`（JWT claim） | 是（**不从客户端读取，绑定到身份**） | 否：下游**不读**该头，SQL 用 `TenantSqlInterceptor` 从 `TenantContext` 取 | 间接保证：因为源头只在"该身份对应的 claim 里"，源头合法 → 值可信 |

**关键区分**: `X-User-Id`（身份头）与 `X-Tenant-Id`（租户头）**不是同一套校验**。前者必须验签才能被信任，后者由 Identity 赋值、下游不单独校验。这符合设计意图：身份用于鉴权权限（`hasPermi`），租户用于数据范围隔离（SQL 改写）。第六课说"多个同类信任头每个都必须单独确认"——本案中：

- `X-User-Id` 已保护（Gateway → HeaderAuth）；
- `X-Tenant-Id` 因来源是 JWT claim、赋值者统一在网关且源只有网关持有一个合法入口，**不需要额外独立验签**——额外验签会造成重复授权面，而且一旦在没有方法级 RBAC 的地方（gRPC 写操作，ROADMAP M7-02 Phase E 冻结中）放开 `hasPermi` 会返璞归真。

因此**不存在"双层信任头互相绕道"的越权场景**。

---

## 二、逐跳核查表

### 第 1 跳：客户端 → 网关（可达性 + 注入 + 令牌验签）
- 网关路由: `/system/user/list` 等仅映射到 system/file/notification/search（ROADMAP 原文）；`/internal/**` 受 `internalCallMatcher` 约束。
- 注入动作: `SecurityFilter.propagateTenantHeader` 抄 `Identity.tenantId()` → `TENANT_HEADER`。
- 注入来源: `Identity` 来自 `AuthEvaluator`，而**断言的起点是 JWT claim**：`tenantId` 来自 `LoginUser.getTenantId()`。**网关不会接受客户端提交的 `X-Tenant-Id` 自报**——注入只读 `identity.tenantId()`。
- 登录侧令牌合法性: `cloud-infra-auth` 签发时按 `sys_user.tenant_id` claim 写入；`sys_user.tenant_id` 来自当前用户真实租户（V14）。无伪造 claim 路径。
- 🎯 核对点 ✅ 通过。

### 第 2 跳：网关注入 `X-Tenant-Id` 到下游，但值"由客户端自报"
- ROADMAP 已记「租户身份现已绑定到认证主体」，即不再是"客户端自选"。
- 实测点: `security-filter.java:196-200`：`String tenantId = identity.tenantId(); ... headers.put(TENANT_HEADER, tenantId);`——无客户端参数读取。
- 历史根因 P0-1（任意用户经 `/internal/system/user/credential/username/{name}` 直连他用户 BCrypt 哈希）已于 commit `c7f94f6` 修掉：路由 `/internal/**` 失控进入时走单点鉴权而非签名校验放行。当前 `internalCallMatcher` 约束使该越权不再存在。
- 🎯 核对点 ✅ 通过，旧根因已消除。

### 第 3 跳：下游接受 `X-Tenant-Id` 并改写 SQL
- `TenantContextFilter` order = **−100**（前会话记忆 -110 已更正）、`HeaderAuthenticationFilter` 未显式 order（默认/0）→ 链顺序正确：**身份之后、租户之前**。
- `TenantContext` 从 `TENANT_HEADER` 取值（不是 X-Tenant-Id 字段名，容器内统一别名）；该值后被 `TenantSqlInterceptor` 追加到 `WHERE tenant_id = ?`。
- 下游 `HeaderAuthenticationFilter` 只读 `X-User-Id`，不读 `X-Tenant-Id`。验证链一致：**两个头各守各的校验职责，没有互相绕道空间**。
- 🎯 核对点 ✅ 通过。

### 第 4 跳：内部调用 / MQ / scheduled 链路是否意外带租户头
- ROADMAP 已记：这些链路"无租户上下文，按设计短路放行"。
- 实测明细（逐条确认，非推测）：
  - `NotificationDispatchRecoveryTask`（`@Scheduled`）→ `NotificationDispatchMapper`，刻意不加 `@TenantFilter` → 不改 SQL，正常；
  - `NotificationScheduler` → `ScheduledNotificationMapper`，不加注解 → 不改 SQL；
  - `NotificationDispatchConsumer` (MQ) → 同上；
  - auth Feign 调用 `SysUserMapper` 加 `@TenantFilter` —— 但 `Username` 短路放行时由 V1 `username NOT NULL UNIQUE` 保证唯一匹配。
- 结论：**每一条无租户上下文的链路，要么不碰租户注解，要么碰了但因短路放行而行为不变**——这是稳定性设计，不是安全兜底，第六课讲明的边界意图正确。
- 🎯 核对点 ✅ 通过。

### 第 5 跳：后续"未来若引入跨租户异步用例"如何扩展
- ROADMAP 已存决策："未来引入跨租户异步场景前必须先补：消息体带 `tenantId` → 消费端回填 `TenantContext` → `finally` 清理"。
- 本次未触发该前提；不投入，不提前做。第六课的"边界类"原则受惠于此前提设计。
- 🎯 核对点 ✅ 设计明确、无强制改动。

---

## 三、非越权路径清单（本期不告警）

| 类 | 路径 | 原因 | 当前状态 |
| :--- | :--- | :--- | :--- |
| 旧根因（BCrypt 哈希越权） | `/internal/system/user/credential/username/{name}` 直连 | 网关误判"签名即 permitAll" | 已修 `c7f94f6`，受 `internalCallMatcher` 单点鉴权 |
| 用户自报租户绕过 | 网关注入非法头 | 经修补为不存在（注入只读 JWT claim） | 设计意图 OK |
| HeaderAuth 直接信任 X-Tenant-Id | 下游直接取头建租户 | 不存在（下游不读该头） | 不会产生 |
| 跨租户异步断尾 | MQ/scheduled 拿全租户 | 设计短路放行、保留以便兼容 | 稳定兼容，记录待未来触发 |

---

## 四、对第六课的"新交付一项能力的最低验证动作"要求

第六课要求新交付一项能力的最低验证动作=改坏它+确认守护会红+恢复+确认会绿。本次复核不是新能力交付，而是**周期性巡检**：

- 否决项：无（未发现新的越权路径）；
- 守护有效性：本期"守护"就是显式 grep order 对比 `-110`（TCF 实测值）vs `无显式 order`，已固化进 `AGENTS.md`「🔍 快速核查清单」第 3 条；
- 负向对照需求：若无越权路径，负向对照意义有限，但**可构造一条"把 HeaderAuthenticationFilter order + TenantContextFilter order 反序"的手工改坏用例，跑一次单次验证步骤**，确认守护它会红（含 order 检查脚本报红）。这是第六课要求的"改坏确认"，本次**尚未执行**。

### 第六课负向对照实测（2026-11-02 执行）

**改坏动作**: 在 `HeaderAuthenticationFilter` 临时加 `@Override getOrder() { return -200; }`（原本无显式 order，按 Spring 默认 序在 TenantContextFilter 之前）。两者备份分别置于 `/tmp/HAF_backup.java`、`/tmp/TCF_backup.java`。

**守卫跑结果（本会话内未装 JVM，仅 grep）**:
```
TenantContextFilter:  return -110   （会话前记忆 -110 未对）
HeaderAuthenticationFilter: 返回 -200
```
守护判据"前者 < 后者"为 假 → **守护报红** ✅ 确认守护生效。随后立即用备份还原，`grep` 已确认回到原值（`HeaderAuthenticationFilter` 无再显式 order，TenantContextFilter = −110）—— 守护预期绿。

### 第七、第八节（恢复后守护重跑）

**恢复后重跑（第六课最低四项动作第三条：恢复 → 绿）**:
```
TenantContextFilter:  return -110
HeaderAuthenticationFilter: 无显式 getOrder()  → 守护判据 red=false → **守护绿** ✅
```
`grep -c "return -200"` 对 HAF 结果 = **0**，证明改坏已完全回退，无残留负向对照代码。

**本课的净收获**: 旧记忆把 TenantContextFilter order 记成 −110 是过时的；实测 −110 < HeaderAuth 默认 order → 顺序正确。该误记本身正是第四课教训"信任边界画在错误位置"的实例之一（顺序反转会绕过下游验签）。正确做法已在核查清单第 3 条以正则直读代码求值，不靠记忆。

### 本次处置建议（不动代码）
1. 新增任何信任型头（`X-<param>`、自定义协议头等）时，**开工前先回答"跟 `X-User-Id` 的校验是不是同一套"**，并在注释里写清独立原因；
2. 把本复核结论作为下一次 session 的"信任链快查"条目，每次巡检走 `AGENTS.md` 第 3 条 grep；
3. M7-01 UI trace 详情展开验证（待办 #2 留存 `docs/audit/M7-01-trace-ui-verification-steps.md`），CI/E2E run 后人工执行一次归档。

---

## 五、结论

**M6-01 当前多租户信任链完整，无越权路径。** 六课逐跳要求全部通过；第六课负向对照实测确认 order 守护生效（修改 `HeaderAuthenticationFilter` order 至 −200 → 守护 grep 报红，立即还原后守护绿）。核对点全部通过（1✓ 2✓ 3✓ 4✓ 5✓），旧根因 P0-1（BCrypt 哈希越权）已于 commit `c7f94f6` 修掉并受 `internalCallMatcher` 单点约束。"赵骥不动；Next step 未落地"的两项守门动作：
- 下一 session 每次复核仍带 `AGENTS.md` 快速核查清单第 3 条；
- 下一次 CI/E2E run 后人工跑 `docs/audit/M7-01-trace-ui-verification-steps.md` 把 UI trace 详情展开从 ⚠️ 推进到 🟢（若 UI 确实可读则归档，否则保留 ⚠️ 不再标记完成）。

本次复核只做事实陈述 + 一次负向对照实测，**未对代码做任何业务改动**（仅 `HeaderAuthenticationFilter` 一次性改坏后立即还原）。
