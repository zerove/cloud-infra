# M7-02: gRPC 支持 (业务模块可选)

Status: in progress (2026-09-18，原生框架完成，系统业务适配层实验性接入，完整验收尚未完成)
Priority: P1
Owner: AI Agent
Module: cloud-infra-framework/framework-grpc + 各业务模块
Depends: 无

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

### 2026-09-18 在途收尾与后续顺序

- 原生框架提交：`8bcbe63`。系统适配层提交：`0c8ba36`，覆盖用户、部门协议及请求边界，默认关闭。
- 系统适配层尚无调用身份、方法权限、租户上下文和数据范围保护，禁止生产开启。密码重置保持未实现。
- 本 issue 原标记 closed 不满足 PRD 的“两业务模块客户端/服务端跑通”条件，因此重新开放。
- 下一步先完成安全边界设计及实现，再接入第二业务模块，验证真实网络调用、拒绝未认证请求、权限及跨租户隔离。
- HTTP 到 gRPC 转换及端到端验收仍待完成，不以 Echo 或模拟业务测试替代。
- 第二业务模块验收前不继续扩大 CDC、模板市场等新功能范围。

- **已解决**: LogNet 3.0.1/5.2.0 不兼容 Spring Boot 4 — 已移除 LogNet 依赖，改为原生实现
- **已解决**: 集成测试 / gRPC 端到端 — 已新增 `GrpcServerLifecycleIntegrationTest` 验证完整流程
- **已解决**: 业务模块需显式引入 LogNet 依赖 — 现无需第三方 starter，仅依赖 io.grpc:* 核心库
- 后续 Phase D (Streaming RPC, grpc-gateway) 推迟到 M8+
- 业务模块接入 gRPC 仍需显式引入 framework-grpc 依赖并添加 `@EnableGrpcServer` 注解

## 不在本 issue 范围

- gRPC 性能压测 (留 benchmark)
- Streaming RPC (Phase D 后, 高并发场景)
- gRPC-Web 浏览器 (M7 后)

## 关联

- PRD M7-02
- 现有 OpenFeign (HTTP 跨服务)
- M6-01 多租户 + M6-02 幂等 (元数据透传到 gRPC headers)
