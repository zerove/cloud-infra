# ADR-007: 模块化框架与自动配置（Starter）模式

**状态**: 已接受  
**日期**: 2026-04-21  
**提出者**: 架构组  

## 背景

多微服务架构需要共享大量横切关注点（安全、缓存、数据访问、链路追踪）。每个服务独立实现这些功能导致代码重复、行为不一致。需要一个标准化的内部 SDK 层。

## 决策

采用 **模块化自动配置（Starter）模式**，将框架能力划分为独立子模块，通过 `cloud-infra-starter` 聚合暴露。

### 模块划分

| 模块 | 能力 | 自动配置类 |
|:---|:---|:---|
| `framework-web` | 虚拟线程、全局异常处理、统一响应、Actuator、TraceId | `GlobalExceptionAutoConfiguration`, `TraceIdAutoConfiguration` |
| `framework-mybatis` | MyBatis-Plus 增强、审计字段、数据权限、字段加密 | `MybatisAutoConfiguration` |
| `framework-redis` | Redisson 封装、分布式锁、限流器 | — |
| `framework-security` | Header 认证过滤器、RBAC 权限校验 | `SecurityAutoConfiguration` |
| `framework-tracing` | Micrometer Tracing 链路追踪 | — |
| `framework-seata` | 分布式事务 | — |

### 自动配置注册

通过 `cloud-infra-starter` 模块中的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件注册所有自动配置类。

### 依赖契约

- `common → framework → starter → modules/gateway/auth`
- 业务模块仅需依赖 `cloud-infra-starter` 和具体 framework 子模块

## 后果

**正面**:
- 统一的行为和数据格式（如统一响应体 `Result<T>`、全局异常处理）
- 新增服务只需引入依赖，无需重复实现基础设施代码
- 框架升级影响范围可控（只需修改 starter 和 framework 子模块）

**负面**:
- 自动配置过多时，排查启动问题较困难
- 需要维护 `AutoConfiguration.imports` 注册表
- 部分模块（如 seata）目前处于配置状态但未充分使用

## 关联

- 相关配置: `AutoConfiguration.imports`
- 相关代码: `CloudInfraRuntimeHints`, `StandardResponseAdvice`, `SecurityAutoConfiguration`
