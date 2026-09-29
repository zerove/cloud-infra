# Session 总结 (2026-09-29)

## 0. 一句话

修复阻塞构建的编译错误, 交付 M7-02 gRPC 调用安全边界, 并把 `master` 上本就失败的
Checkstyle 门禁清零、顺带修掉 10 项真实缺陷。

## 1. 起始状态与问题

工作区有 5 个未提交改动 + 1 个未跟踪测试文件, 且 `mvn test` **编译失败**:
`SearchTestApplication` 使用 `@Bean` 但缺少 `org.springframework.context.annotation.Bean` 导入。

| # | 问题 | 严重度 | 处理 |
|---|---|---|---|
| 1 | `SearchTestApplication` 缺 `@Bean` import, 全量测试无法编译 | 阻塞 | `4d8a14a` |
| 2 | gRPC 独立端口无任何调用认证, 仅靠传输加密 | 高 | `7be334b` |
| 3 | `X-Trace-Id` 未校验即写入 MDC 与响应头 (日志伪造) | 高 | `e51ea3d` |
| 4 | `WorkflowStep.timeout` 计算后从未使用, 步骤级超时形同虚设 | 中 | `e51ea3d` |
| 5 | `TemplateEngine` / `OpenApiExporter` 对无父目录路径 NPE | 中 | `e51ea3d` / `8ba2571` |
| 6 | `CodegenCli` Scanner 跟随平台默认编码 | 中 | `e51ea3d` |
| 7 | `WorkflowEngine` 死方法 `executeCompensation` + 死存储 | 低 | `e51ea3d` |
| 8 | `DataScopeInterceptor` 只写不读的 `roleIds` | 低 | `c1e1437` |
| 9 | `SysLoginService.passwordEncoder` 只写不读 | 低 | 记入 issue 26 |
| 10 | `EnhancedDistributedLock` 健康探测吞异常且不留痕 | 低 | `e51ea3d` |

**额外发现**: `master` 上 CI 质量门本就失败 —
`mvn checkstyle:check` 报 35+ 处违规, `mvn spotbugs:check` 报数十项。

## 2. 交付内容

### 2.1 M7-02 gRPC 调用安全边界 (`7be334b`)

gRPC 独立网络端口不经过网关过滤链, 此前仅有 TLS 传输加密。本次补齐三道边界:

| 边界 | 机制 | 失败结果 |
|---|---|---|
| 内部调用签名 | `X-Internal-Call` HMAC, 与 HTTP 侧同一密钥与 5 分钟时间窗 | `UNAUTHENTICATED` |
| 调用方身份 | `X-User-Id` / `X-User-Name` | `UNAUTHENTICATED` |
| 租户上下文 | `X-Tenant-Id` → `TenantContext`, 使 MyBatis 租户拦截器生效 | `UNAUTHENTICATED` |

错误描述统一为 `Unauthenticated`, 不区分"缺签名"与"签名错误", 避免攻击者据此枚举。

**分层设计**:
- `cloud-infra-common`: `PlatformCaller` + `CallerContextBinder` SPI — 身份模型与传输协议无关,
  HTTP / gRPC 入口共用同一套下游鉴权链路
- `framework-grpc`: `GrpcSecurityInterceptor` + `GrpcCallerContext` (gRPC 原生 `Context`)
- `framework-security`: `SpringSecurityCallerContextBinder` — 通过 SPI **反向**装配
  `SecurityContextHolder`, 使 `framework-grpc` 编译期不引用 spring-security, 避免双向依赖

线程上下文仅在业务代码执行期间存在 (每个监听器回调成对绑定/清理), 不跨调用残留。

**测试 19 项**:
- `GrpcSecurityInterceptorTest` (12) — 进程内真实调用链: 7 类拒绝场景 + 放行 + 无残留 + 关闭开关零开销
- `SpringSecurityCallerContextBinderTest` (6) — 桥接行为 + 自动配置条件
- `SystemGrpcSecurityBoundaryTest` (1) — **真实 TCP+TLS** + 完整 Spring 条件装配

### 2.2 质量门清零

| 门禁 | 起始 | 现状 |
|---|---|---|
| `mvn test` | 编译失败 | **825 项 0 失败** |
| `mvn checkstyle:check` | 35+ 违规 | **全 21 模块 0 违规** |
| `mvn spotbugs:check` | 数十项 | 12 个模块 0, 剩 5 个业务模块 (issue 26) |

误报豁免全部在 `spotbugs-exclude.xml` 附理由注释, 无裸排除。

## 3. 关键决策与理由

1. **SPI 放在 common 而非 framework-grpc** — 否则 `framework-security` 需反向依赖
   `framework-grpc`, 与后者的 optional 依赖构成 Maven 循环。
2. **步骤级超时在 handler 返回后核对耗时, 而非嵌套 future** — 引擎用固定 8 线程池且
   `executeStep` 已占用其中一线程, 嵌套提交并等待会在并发步骤耗尽池时死锁。
3. **TraceId 丢弃重生成而非清洗后复用** — 清洗易被绕过, 且让"值可疑"这一信号丢失。
4. **vendored Spring Cloud 文件用定向豁免而非改写** — 保留上游原始制表符以便 diff 与升级替换。
5. **质量门修复独立成 3 个 commit** — 与功能提交分离, 便于评审与回滚。

## 4. 遗留风险 (必须如实记录)

| 项 | 状态 | 说明 |
|---|---|---|
| gRPC 方法级权限 | **未完成** | `hasPermi` 在 gRPC 通道内恒为 false → **写操作禁止放开** |
| gRPC 数据范围过滤 | **未完成** | 入口未提供 `deptId`, `@DataScope` 不可用 |
| gRPC 第二业务模块 | **未完成** | 尚无客户端接入, PRD"两业务模块跑通"未满足 |
| HTTP→gRPC 转换 | **未完成** | grpc-gateway 推迟到 M8+ |
| M7-03 生产入口验收 | **未完成** | 反向代理、可信网关、容器证书挂载未验证 |
| SpotBugs 剩余 | 部分 | issue 26, 含 `SecurityUser` 未覆写 `equals` 的待判断项 |

## 5. 后续开发计划

### 阶段 1: gRPC 授权补齐 (M7-02 Phase E) — 最高优先

阻断生产开启的唯一路径。顺序不可颠倒:

1. **权限来源**: 在 `PlatformCaller` 基础上接入 `IPermissionProvider`, 使 gRPC 通道内
   `hasPermi` 可用。需避免与 `HeaderAuthenticationFilter` 重复拉取。
2. **方法级映射**: 建立 `服务/方法 → 权限标识` 映射 (配置驱动优先, 与 HTTP 侧
   `@PreAuthorize("system:user:list")` 对齐), 未映射的写方法**默认拒绝**。
3. **数据范围**: 扩展 `PlatformCaller` 携带 `deptId`, 使 `@DataScope` 可用。
4. **验收**: 真实 TLS 下断言"无权限调用写方法返回 `PERMISSION_DENIED`"、
   "跨租户查询隔离"。

### 阶段 2: 第二业务模块接入 — 验证跨服务

选 notification 或 file 作为第二模块: 定义 proto → 服务端适配层 → 客户端 stub →
跨服务网络集成测试 (两个真实进程或容器间 TCP+TLS), 验证签名透传与租户透传。

### 阶段 3: M7-03 生产入口验收

可信网关身份校验、浏览器升级握手、完整安全过滤链、容器证书挂载、管理健康检查。
**不得用隔离组件测试替代**。

### 阶段 4: 质量门收尾 (issue 26)

清零剩余 5 个业务模块的 SpotBugs 存量。其中 `SecurityUser` 未覆写
`equals`/`hashCode` 需给出明确结论, 不允许静默忽略。

### 阶段 5: M7-07 性能基准, 再推进 CDC / 模板市场 / 编辑器插件

前置验收全部通过后才扩大功能范围。

## 6. 验证记录

```
mvn -o test              → BUILD SUCCESS, 825 tests, 0 failures
mvn -o checkstyle:check  → BUILD SUCCESS (全 21 模块 0 违规)
bash tools/check-doc-versions.sh → 全部版本一致
```

## 7. 提交

| Commit | 内容 |
|---|---|
| `4d8a14a` | 修复测试编译错误 + gRPC TLS 网络验收测试 |
| `7be334b` | M7-02 调用安全边界 (签名 + 身份 + 租户) |
| `e51ea3d` | Checkstyle 全部清零 + common/codegen/tracing/security/redis/api 的 SpotBugs |
| `8ba2571` | framework-web 与 gateway 的 SpotBugs + 6 项真实缺陷 |
| `c1e1437` | framework-mybatis 与 starter 的 SpotBugs |
