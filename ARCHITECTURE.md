# 架构定义 (ARCHITECTURE.md)

## 📐 分层结构 (Layering)
- **API Layer**: 
    - `cloud-infra-api`: 定义全平台共享的 Feign 客户端接口及 DTO，作为服务间契约。
    - `Controller`: 各模块入口，负责请求接收、参数校验及响应封装。
- **Service Layer**: 
    - `Service` 接口与 `ServiceImpl` 实现：承载核心业务逻辑、事务控制及跨模块协同。
- **Data Access Layer**: 
    - `Mapper/Repository`: 基于 MyBatis-Plus (PostgreSQL) 或 Elasticsearch 实现数据持久化与查询。
    - `Infrastructure`: 封装 Redis 缓存策略、RabbitMQ 消息生产/消费。

## 🔗 模块依赖图 (Dependency Graph)
- `cloud-infra-modules` $\rightarrow$ `cloud-infra-framework` (原因: 使用内部 SDK 能力如 Security, Tracing, Redis)
- `cloud-infra-modules` $\rightarrow$ `cloud-infra-common` (原因: 依赖通用工具类及全局异常定义)
- `cloud-infra-modules` $\rightarrow$ `cloud-infra-api` (原因: 调用其他服务的 Feign 接口)
- `cloud-infra-gateway` $\rightarrow$ `cloud-infra-auth` (原因: 依赖认证中心进行 Token 校验与身份验证)
- `cloud-infra-codegen` $\rightarrow$ `cloud-infra-common` (原因: 依赖通用工具类; 同时作为开发期工具, 不打包进运行时镜像)

## 🛠️ 代码生成框架 (cloud-infra-codegen, 2026-09)

- **核心库** `cloud-infra-codegen-core`: 10+ FreeMarker 模板 (entity / mapper / CreateDTO / UpdateDTO / VO / Converter / service / impl / controller / schema.sql)
- **Maven 插件** `cloud-infra-codegen-maven-plugin`: JDK 17 编译, 自包含精简模板, 集成到构建流程
- **FlywayDdlParser**: 从 Flyway DDL 文件解析表元数据, 无需 DB 连接, 支持 PostgreSQL 16 完整特性 (SMALLINT / BIGSERIAL / VARCHAR(N) / UNIQUE / DEFAULT / @TableLogic)
- **AOP 集成** (`@BusinessMetric`): 业务方法标注注解即可埋点, Counter 按需注册, 避免 try-catch (合规 adg java-catch-all 规则)

## ⚡ 关键设计决策 (ADR - Architecture Decision Records)
- **决策 1**: 采用 **JDK 25 虚拟线程** $\rightarrow$ [理由: 消除传统线程池瓶颈，实现极高并发下的轻量级阻塞，提升吞吐量]
- **决策 2**: 启用 **Generational ZGC** $\rightarrow$ [理由: 维持微秒级 GC 停顿，确保高并发下的低延迟响应]
- **决策 3**: 采用 **Nacos** 作为配置/注册中心 $\rightarrow$ [理由: 实现动态配置秒级生效，简化微服务治理]
- **决策 4**: 部分模块（Gateway/Auth）构建 **Native Image** $\rightarrow$ [理由: 极速启动与极低内存占用，优化 K8s 弹性伸缩]

## 🔒 边界与契约 (Boundaries & Contracts)
- **内部通信**: 
    - 同步：使用 OpenFeign 遵循 REST 规范。
    - 异步：使用 RabbitMQ 实现服务间解耦。
- **外部接口**: 
    - 统一采用 RESTful 风格，返回格式为 `Result<T>` 统一响应体。
    - 身份信息通过 `X-User-Id` 等自定义 Header 在网关与下游服务间传递。
