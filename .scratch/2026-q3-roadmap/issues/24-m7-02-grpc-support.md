# M7-02: gRPC 支持 (业务模块可选)

Status: frozen (2026-09-29，调用安全边界已交付并经真实 TLS 验收后冻结；详见下方“冻结决定”)
Priority: P1
Owner: AI Agent
Module: cloud-infra-framework/framework-grpc + 各业务模块
Depends: 无

## ⛔ 冻结决定 (2026-09-29)

**保留代码, 暂停一切投入。**

按 issue 27 落地的《框架能力准入原则》对本 issue 自查:

| 准入原则 | 本 issue 现状 | 结论 |
|---|---|---|
| 先有第二个真实用例, 再有抽象 | 仅 system-module 一个适配层, **无第二业务模块客户端** | ❌ 不满足 |
| 提出来源必须是业务, 不是设想 | PRD 写的是"移动端/IoT 场景", 至今无对应客户端 | ❌ 不满足 |
| 判定完成必须有业务引用 | Phase A-D 全部完成, 业务引用 1 个且开关默认关闭 | ❌ 不满足 |
| 抽象层数不超过必要 | 独立模块 + 传输层 + 生命周期 + 安全边界, 为单一使用方 | ⚠️ 勉强（已单层化） |

且本 issue 自定的 PRD 验收标准 —— **"两业务模块客户端/服务端跑通"** —— 至今未达成。
在这一点上, 本 issue 与被降级的 M6 存量组件 (WorkflowEngine / Outbox / 幂等 / codegen)
并无本质差别: 都是"代码完成、自测通过、但没有第二个真实使用方"。

**为何不删除**: 传输层 + 生命周期 + 安全边界本身实现完整且有测试覆盖
(含真实 TLS 网络验收), 删除是净损失。

**解冻前提**: 出现明确的 gRPC 使用方 (如移动端或 IoT 客户端接入), 且能说清
"哪个现有业务场景现在就需要它"。届时重新评估是否需要补 Phase E。

**解冻后仍需完成** (当前不投入): 方法级权限映射、数据范围过滤、第二业务模块客户端、
HTTP→gRPC 转换。在 `GrpcCaller` 不携带权限集合的前提下, **写操作禁止放开**。

## 背景 (PRD M7-02)

> gRPC 支持 (业务模块可选). 移动端/IoT 场景.

## 现状盘点 (2026-09-05)

| 模块 | 文件 | 状态 | 备注 |
|---|---|---|---|
| framework-web | `feign-core` | ✅ 已用 | OpenFeign HTTP REST 跨服务调用 |
| 各业务模块 | spring-cloud-starter-openfeign | ✅ 已用 | 微服务间同步调用 |
| framework | grpc-netty / protobuf | ❌ 缺失 | 无 gRPC 依赖 |
| **.proto 文件** | 不存在 | ❌ 缺失 | 需先定义 IDL |
| **protoc 编译插件** | 不存在 | ❌ 缺失 | protobuf-maven-plugin |
| **gRPC server/client Spring 集成** | 不存在 | ❌ 缺失 | grpc-spring-boot-starter |
| **gRPC + 现有 Feign 兼容** | 不存在 | ❌ 缺失 | 双协议网关 (HTTP + gRPC) |

## 关键澄清: gRPC vs Feign

- **Feign** (已用): HTTP REST, 同步阻塞, JSON 序列化, 跨语言能力弱
- **gRPC** (待加): HTTP/2, 双向流, Protobuf 高效序列化, 强跨语言

两者**互补不冲突**: 内部微服务用 Feign (REST 简单), 移动端/IoT 外部用 gRPC (性能 + 跨语言).

## 验收标准 (PRD)

> M7-02: gRPC 支持 (业务模块可选)

需补 3 项核心能力:
1. **proto IDL 定义**: 业务服务接口的 .proto 文件
2. **gRPC server 集成**: 业务模块可选启用 gRPC server 暴露同一 API
3. **gRPC client + 网关**: grpc-gateway 或 Envoy 支持 HTTP → gRPC 转换

## 架构选型 (待架构师评审)

### 选项 A: spring-grpc (官方)

- 优点: Spring 官方, 集成度高
- 缺点: 较新, 文档少
- 工作量: 5-7d

### 选项 B: grpc-spring-boot-starter (LogNet)

- 优点: 社区成熟, 文档全
- 缺点: 第三方维护
- 工作量: 5-7d

### 选项 C: 自研 protoc + grpc-netty 集成

- 优点: 完全控制
- 缺点: 工作量大
- 工作量: 10-14d

**建议**: 选项 B (grpc-spring-boot-starter, 社区成熟)

## 实施阶段 (估时 1w, 选项 B)

### Phase A: 基础设施 (2d)

1. **新增 `framework-grpc` 模块** (新 module): 封装 gRPC server/client 配置
2. **`@EnableGrpcServer` 注解**: 业务模块加一行启用
3. **protoc 编译插件**: protobuf-maven-plugin 自动生成 Java 代码
4. **Hello proto**: 演示用 service.proto (Echo + Health)

### Phase B: 业务应用 (3d)

1. **选 1-2 业务模块** (如 system-module) 加 gRPC server
2. **proto 定义**: SysUserService, SysDeptService 接口
3. **实现**: gRPC adapter 调用现有 service
4. **gRPC client demo**: 跨服务 gRPC 调用 (替换 1 个 Feign 调用)

### Phase C: 网关 + 测试 + 文档 (2d)

1. **grpc-gateway**: HTTP → gRPC 转换 (前端仍用 REST)
2. **集成测试**: gRPC 客户端/服务端 e2e
3. **ADR-013**: gRPC 选型 + 性能对比

### Phase D: 调用安全边界 (2026-09-29 已完成)

1. **内部调用签名**: 复用 `InternalCallCrypto` HMAC, 与 HTTP 侧同一密钥与时间窗
2. **调用方身份**: `PlatformCaller` + `GrpcCallerContext` + `CallerContextBinder` SPI
3. **租户上下文**: 绑定 `TenantContext`, 使 MyBatis 租户拦截器在 gRPC 链路生效
4. **Spring Security 桥接**: `framework-security` 反向装配, 业务层复用既有鉴权链路

## 关键设计

### 双协议模块 (Feign + gRPC 共存)

```java
// 业务模块主类
@SpringBootApplication
@EnableFeignClients
@EnableGrpcServer  // <-- 一行启用
public class SystemApplication { }

// 服务接口 (Feign)
@FeignClient(name = "system")
public interface SysUserFeignClient { ... }

// gRPC 等价 (自动生成)
public class SysUserServiceGrpc.SysUserServiceBlockingStub { ... }
```

### proto 示例 (system.proto)

```protobuf
syntax = "proto3";
package cloudinfra.system;

option java_package = "com.cloudinfra.system.grpc";
option java_multiple_files = true;

service SysUserService {
  rpc GetUser(GetUserRequest) returns (UserResponse);
  rpc ListUsers(ListUsersRequest) returns (ListUsersResponse);
}

message UserResponse {
  int64 user_id = 1;
  string username = 2;
  // ... 与 Java DTO 同步
}
```

## 进度

- [x] **2026-09-05 03:10**: kickoff + infra survey (本 issue)
- [x] **2026-09-06**: 选项 B 选定 (LogNet grpc-spring-boot-starter)
- [x] **2026-09-06**: Phase A 完成 (commit 8ace1d8): framework-grpc 模块 + @EnableGrpcServer + CloudInfraGrpcProperties + GrpcServerAutoConfiguration + echo.proto + protobuf 编译插件
- [x] **2026-09-07**: Phase B 完成 (commit 3dc6649): EchoServiceImpl + 5 单元测试 (5/5 pass)
- [x] **2026-09-07**: Phase C 完成 (commit 774f10c): LogNet optional + @ConditionalOnClass 守护 (LogNet 3.0.1/5.2.0 不兼容 SB4, 业务模块按需显式引入)
- [x] **2026-09-07**: 文档同步 (commit e917312): ROADMAP.md 状态更新
- [x] **2026-09-07**: 推送到 origin/master (6 commits ahead)
- [x] **2026-09-09**: **Phase C 原生重构完成** (本次提交): 移除 LogNet 依赖, 实现原生 Spring Boot 4 兼容的 gRPC 自动配置
  - 升级 gRPC 1.65.1 → 1.80.0, protobuf 3.25.5 → 4.34.2
  - 新增 `GrpcServerLifecycle`: SmartLifecycle + InitializingBean + DisposableBean 完整生命周期管理
  - 重构 `GrpcServerAutoConfiguration`: 基于 `@ConditionalOnProperty` 自动发现 `BindableService` bean
  - 扩展 `CloudInfraGrpcProperties`: 支持 keep-alive, SSL/TLS, 进程内模式, 连接池配置
  - 更新 `EchoServiceImpl`: 使用 `@Component` 替代 `@GRpcService`, 由原生配置自动发现
  - 新增集成测试 `GrpcServerLifecycleIntegrationTest`: 验证启动/停止/RPC调用全流程
  - 单元测试 5/5 pass, 集成测试 4/4 pass, 编译通过

## 已知约束 (M8+ 议题)

### 2026-09-29 Phase D: 调用安全边界（签名 + 身份 + 租户）

**已交付**:

- `cloud-infra-common`: `PlatformCaller`（协议无关调用方身份）+ `CallerContextBinder` SPI。
  放在 common 是因为身份模型与传输协议无关，HTTP / gRPC 入口共用同一套下游鉴权逻辑。
- `framework-grpc`: `GrpcSecurityInterceptor`（`ServerInterceptor`）+ `GrpcCallerContext`
  （gRPC 原生 `Context`）+ `cloudinfra.grpc.security.*` 配置段。拦截器校验内部调用 HMAC 签名、
  提取 `X-User-Id` / `X-User-Name` / `X-Tenant-Id`，绑定 `TenantContext` 并把身份写入
  gRPC 原生上下文；`GrpcServerLifecycle` 新增 `ServerInterceptor` 链支持。
- `framework-security`: `SpringSecurityCallerContextBinder` + `GrpcSecurityBridgeAutoConfiguration`，
  通过 SPI 反向装配 `SecurityContextHolder`，使业务层可直接用既有 `SecurityUtils` / `TenantContext`。
  framework-grpc 编译期不引用 spring-security，避免双向依赖。

**验收证据**:

- `GrpcSecurityInterceptorTest`（12 项，进程内真实调用链）：无签名 / 伪造签名 / 时间窗外 /
  时间戳非法 / 缺租户 / 缺身份 / 非法 userId 全部 `UNAUTHENTICATED`；错误描述统一为
  `Unauthenticated`，不区分失败原因；合法调用放行且身份/租户在业务执行期间可见；
  `bind`/`clear` 计数相等，调用结束后无线程上下文残留；关闭开关时零校验零开销。
- `SpringSecurityCallerContextBinderTest`（6 项）：`SecurityUtils.getUserId()` 可读到调用方；
  `clear()` 后上下文为空；未引入 framework-grpc 时自动配置不装配多余 bean。
- `SystemGrpcSecurityBoundaryTest`（1 项，**真实 TLS 网络调用** + 完整 Spring 条件装配）：
  `CallerContextBinder` 实际装配为 Spring Security 实现；无签名 / 伪造签名 / 缺租户被拒；
  合法签名 + 完整身份放行，且 `TenantContext` / `SecurityUtils` 在 `ISysUserService`
  实现体内读到 `tenant-x` / `5L`；调用结束后上下文不残留。
- 全量回归：`mvn test` 809 项测试 0 失败（新增 19 项）。

**仍缺失（禁止生产开启的前提）**:

- **方法级权限映射**：`hasPermi` 在 gRPC 通道内恒为 false（无权限来源），
  写操作（create/update/delete）不得放开。需先接入权限提供者。
- **数据范围过滤**：业务侧 `@DataScope` 依赖 `SecurityUtils.getDeptId()`，
  gRPC 入口未提供 `deptId`，需扩展 `PlatformCaller` 或补齐身份拉取。
- 第二业务模块客户端接入与跨服务网络调用。
- HTTP → gRPC 协议转换。

### 2026-09-18 在途收尾与后续顺序

- 原生框架提交：`8bcbe63`。系统适配层提交：`0c8ba36`，覆盖用户、部门协议及请求边界，默认关闭。
- 真实 TLS 传输层验收：`4d8a14a` 新增 `SystemGrpcNetworkTest`（`file:` 证书路径 + 真实 TCP/TLS）。
- 系统适配层的调用身份、方法权限、租户上下文和数据范围保护已完成前两项（身份 + 租户），
  详见上方 Phase D。密码重置保持未实现。
- 本 issue 原标记 closed 不满足 PRD 的“两业务模块客户端/服务端跑通”条件，因此重新开放。
- 下一步补方法级权限映射与数据范围，再接入第二业务模块，验证跨租户隔离与拒绝未授权调用。
- HTTP 到 gRPC 转换及端到端验收仍待完成，不以 Echo 或模拟业务测试替代。
- 第二业务模块验收前不继续扩大 CDC、模板市场等新功能范围。

- **已解决**: LogNet 3.0.1/5.2.0 不兼容 Spring Boot 4 — 已移除 LogNet 依赖，改为原生实现
- **已解决**: 集成测试 / gRPC 端到端 — `GrpcServerLifecycleIntegrationTest` + `SystemGrpcNetworkTest`（真实 TLS）
- **已解决**: 业务模块需显式引入 LogNet 依赖 — 现无需第三方 starter，仅依赖 io.grpc:* 核心库
- **已解决**: `file:` 前缀证书路径不被识别 — `GrpcServerLifecycle.resolveResourceAsStream` 已支持
- 后续 Phase E (Streaming RPC, grpc-gateway) 推迟到 M8+
- 业务模块接入 gRPC 仍需显式引入 framework-grpc 依赖并添加 `@EnableGrpcServer` 注解

## 不在本 issue 范围

- gRPC 性能压测 (留 benchmark)
- Streaming RPC (Phase E 后, 高并发场景)
- gRPC-Web 浏览器 (M7 后)

## 关联

- PRD M7-02
- 现有 OpenFeign (HTTP 跨服务)
- M6-01 多租户 + M6-02 幂等 (元数据透传到 gRPC headers)
