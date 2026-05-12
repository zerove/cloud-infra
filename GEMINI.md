# 云基础设施平台 (Cloud-Infra)

一个基于 Spring Boot 4, JDK 25 和虚拟线程 (Virtual Threads) 构建的现代化、高性能云基础设施平台。

## 项目概览

本项目旨在为微服务提供基础架构支撑，专注于高并发处理、分布式架构和开发者生产力。

- **核心技术栈**: Spring Boot 4.0.5, JDK 25, Spring Cloud 2025.
- **微服务框架**: Spring Cloud Alibaba (Nacos 用于配置/注册中心, Seata 用于分布式事务)。
- **持久化**: MyBatis Plus 3.5.6, MySQL 8.4 / PostgreSQL 16.
- **缓存与并发**: Redisson 3.30.0.
- **可观测性**: Micrometer Tracing, Prometheus, Actuator.
- **安全性**: RBAC 权限模型, JWT (jjwt 0.12.5), Spring Security.
- **辅助工具**: Lombok, Hutool, Testcontainers.

## 架构设计

项目采用 Maven 多模块结构，位于 `cloud-infra/` 目录下。

- **`cloud-infra-gateway`**: 基于 Spring Cloud Gateway (MVC) 的统一入口 (端口: 9000)。
- **`cloud-infra-auth`**: 集中式认证与授权服务 (端口: 9200)。
- **`cloud-infra-framework`**: 核心框架模块 (包含 Web, MyBatis, Redis, Security, Tracing)。
- **`cloud-infra-common`**: 共享工具类, 常量和基类。
- **`cloud-infra-modules`**:
  - `system-module`: 核心管理与 RBAC 服务 (端口: 9300)。
  - `file-service`: 对象存储与文件管理服务。
  - `notification-service`: 消息推送与通知交付服务。
  - `search-service`: 基于 Elasticsearch 的搜索服务。
- **`cloud-infra-starter`**: 项目启动入口与引导配置。
- **`cloud-infra-api`**: 供服务间通信的 Feign 客户端和 DTO 定义。
- **`cloud-infra-test-support`**: 基于 Testcontainers 的集成测试基础设施。

## 构建与运行

### 前置条件
- JDK 25
- Maven 3.9+
- Podman / Docker (用于编排)

### 构建
在 `cloud-infra/` 根目录下执行:
```bash
mvn clean install -DskipTests
```

### 运行 (开发环境)
`cloud-infra/deploy` 目录包含使用 `podman-compose` 进行本地编排的脚本。

1. **启动基础设施与服务**:
   ```bash
   cd cloud-infra/deploy
   ./up.sh
   ```
   该脚本将执行以下操作:
   - 启动中间件: Nacos (8848), Redis (6379), PostgreSQL (5432)。
   - 将配置同步至 Nacos。
   - 启动微服务: Gateway, Auth, System。
   - 配置 Prometheus 监控。

2. **访问入口**:
   - **Nacos 控制台**: http://localhost:8848/nacos
   - **API 网关**: http://localhost:9000
   - **系统模块**: http://localhost:9300

## 开发约定

- **语言要求**: 项目所有文档、代码注释及提交信息必须使用 **中文**。
- **JDK 25 特性**: 利用虚拟线程 (Virtual Threads) 处理高吞吐 I/O 操作。
- **Lombok**: 使用 `@Data`, `@Slf4j` 以及通过 `@RequiredArgsConstructor` 进行构造函数注入。
- **MyBatis Plus**: 遵循 Mapper/Service/ServiceImpl 标准模式。使用 `LambdaQueryWrapper` 实现类型安全的查询。
- **安全性**: RBAC 在网关和服务端双重强制执行。方法级安全使用 `@PreAuthorize`。
- **链路追踪**: 所有请求通过 Micrometer 追踪。确保日志中包含 `traceId` 和 `spanId`。
- **测试**: 新功能必须包含基于 `BaseIntegrationTest` (利用 Testcontainers) 的集成测试。
- **配置**: 业务配置统一在 Nacos 管理。本地 `application.yml` 仅包含引导/环境特定设置。
