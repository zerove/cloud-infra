# M7-02: gRPC 支持 (业务模块可选)

Status: open (survey done 2026-09-05, 估时 1w, 大型基础设施改造)
Priority: P1
Owner: AI Agent (待架构师评审)
Module: cloud-infra-framework/framework-web + 各业务模块
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
- [ ] **待架构师评审**: 选项 A/B/C 选型
- [ ] Phase A: framework-grpc 模块 + 注解 + protoc
- [ ] Phase B: 业务模块 gRPC 接入
- [ ] Phase C: grpc-gateway + 测试 + ADR-013

## 不在本 issue 范围

- gRPC 性能压测 (留 benchmark)
- Streaming RPC (Phase D 后, 高并发场景)
- gRPC-Web 浏览器 (M7 后)

## 关联

- PRD M7-02
- 现有 OpenFeign (HTTP 跨服务)
- M6-01 多租户 + M6-02 幂等 (元数据透传到 gRPC headers)