# Cloud-Infra 平台系统架构与分析总结 (AGENTS.md)

本文件基于对当前 Cloud-Infra 源码仓库的深度扫描与分析构建，汇总了系统架构、模块目录结构、核心业务流程以及底层的依赖技术。

---

## 一、项目整体架构 (Overall Architecture)

Cloud-Infra 是一个基于现代化 Java 生态构建的、高性能、企业级的微服务基础设施平台。整个系统围绕**极致高并发**、**极低延迟**以及**云原生微服务设计**进行研发：

1. **统一网关与安全模型**：
   - 外部请求统一经过网关 [cloud-infra-gateway](file:///home/zeron/dev/cloud-infra/cloud-infra-gateway) 模块。网关基于 Spring Cloud Gateway MVC 模式，执行全局身份校验、链路追踪 ID 生成、限流和路由转发。
   - 平台采用**分布式令牌认证模型**。客户端在 [cloud-infra-auth](file:///home/zeron/dev/cloud-infra/cloud-infra-auth) 进行登录认证并获取 JWT Token，在后续请求中携带该 Token。
2. **高并发与低延迟基座 (JDK 25 + Virtual Threads)**：
   - 项目深度整合 **JDK 25** 的 **虚拟线程 (Virtual Threads)**。每个微服务通过 `spring.threads.virtual.enabled=true` 彻底消除了传统平台“一连接一线程”的瓶颈。
   - 配合 **Generational ZGC (分代无停顿垃圾收集器)**，在超高并发下保持极低的 GC 停顿时间（微秒级），极大地提升了系统的吞吐量与稳定性。
3. **高弹性的数据与中间件架构**：
   - 缓存与锁：深度依赖 **Redis** 以及 **Redisson**（提供高可靠性的红锁与异步限流）。
   - 持久化：基于 **PostgreSQL** 并整合 **MyBatis-Plus**，支持多模块的持久化和逻辑删除。
   - 检索与分析：基于 **Elasticsearch** 建设分布式检索体系，并由 [search-service](file:///home/zeron/dev/cloud-infra/cloud-infra-modules/search-service) 模块承载操作日志的高效检索。
   - 消息路由：整合 **RabbitMQ**，用于在通知系统、设备数据接收中实现削峰与异步解耦。
4. **云原生 AOT 与 Native Image**：
   - 项目完全支持 **GraalVM Native Image** 构建（参考 [DOCKERFILES.md](file:///home/zeron/dev/cloud-infra/deploy/DOCKERFILES.md) 中的 `Dockerfile.native` 阶段）。在 Native 构建下可以实现零秒级冷启动、极致的运行期内存节省和轻量化部署。

---

## 二、目录结构与模块说明 (Directory Structure)

整个项目采用多模块 Maven 进行管理（顶层定义：[pom.xml](file:///home/zeron/dev/cloud-infra/pom.xml)），模块划分清晰，职责分明。目录布局及核心定义如下：

```
cloud-infra (Root)
├── pom.xml                               # 顶级 Maven 依赖及版本管理
├── configs/                              # Nacos 本地配置文件目录
│   ├── gateway.yaml                      # 网关服务配置
│   ├── auth.yaml                         # 认证服务配置
│   └── system-module.yaml                # 系统模块配置
├── deploy/                               # 容器化部署、Prometheus 等配置
│   └── DOCKERFILES.md                    # 原生镜像与 JRE 编译多阶段 Dockerfile
├── cloud-infra-starter/                  # 引导与懒加载核心启动组件
├── cloud-infra-common/                   # 公共工具、通用 DTO、事件与全局异常
├── cloud-infra-framework/                # 内部 SDK 底座，包含各个底层技术能力的封装
│   ├── framework-mybatis                 # 逻辑删除、主键生成与 MyBatis 配置
│   ├── framework-redis                   # 基于 Redisson 锁及 Redis 限流封装
│   ├── framework-seata                   # 分布式事务分布式一致性支持
│   ├── framework-security                # 下游 SecurityContext 鉴权解析、RBAC 表达式
│   ├── framework-tracing                 # Micrometer 分布式链路追踪与 MDC 日志
│   └── framework-web                     # 统一全局拦截器、虚拟线程调度、API 包装与监控
├── cloud-infra-api/                      # Feign RPC 客户端及共享 DTO 接口
├── cloud-infra-gateway/                  # 微服务统一网关服务入口
├── cloud-infra-auth/                     # 统一身份认证与授权服务 (Token 签发与刷新)
├── cloud-infra-modules/                  # 核心业务微服务
│   ├── system-module                     # 系统核心服务 (用户、RBAC权限、角色、配置等)
│   ├── file-service                      # 文件对象存储及文件服务
│   ├── notification-service              # 消息发布、推送与通知历史记录服务
│   ├── search-service                    # 基于 Elasticsearch 的分布式检索
│   └── iot-gateway-service               # 工业物联网边缘设备网关
└── cloud-infra-test-support/             # 基于 Testcontainers 的单元和集成测试底座
```

### 核心微服务与主启动类路径
- **网关入口**：[GatewayApplication.java](file:///home/zeron/dev/cloud-infra/cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/GatewayApplication.java) - 负责引导网关，并且注入 AOT 构建中对 RouterFunction 的特殊预处理以兼容 Spring Cloud Gateway MVC 运行。
- **认证中心**：[AuthApplication.java](file:///home/zeron/dev/cloud-infra/cloud-infra-auth/src/main/java/com/cloudinfra/auth/AuthApplication.java) - 注入 Feign Clients 开启能力并加载全局 [cloud-infra-common](file:///home/zeron/dev/cloud-infra/cloud-infra-common) 公共依赖。
- **系统核心**：[SystemApplication.java](file:///home/zeron/dev/cloud-infra/cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/SystemApplication.java) - 开启事务管理，扫描 framework 底层 SDK 并引导整个系统运行。
- **文件服务**：[FileApplication.java](file:///home/zeron/dev/cloud-infra/cloud-infra-modules/file-service/src/main/java/com/cloudinfra/file/FileApplication.java) - 扫描 MyBatis Mapper 并通过 ComponentScan 自动配置 framework 底层功能。
- **通知中心**：[NotificationApplication.java](file:///home/zeron/dev/cloud-infra/cloud-infra-modules/notification-service/src/main/java/com/cloudinfra/notification/NotificationApplication.java) - 配置了 `@EnableAsync` 异步特性（自动利用底层虚拟线程池）并开启懒加载来加快容器冷启动。
- **高性能搜索**：[SearchApplication.java](file:///home/zeron/dev/cloud-infra/cloud-infra-modules/search-service/src/main/java/com/cloudinfra/search/SearchApplication.java) - 引导 Elasticsearch 存储和索引映射。

---

## 三、核心业务流程 (Core Business Processes)

### 1. 用户登录与令牌签发流程
- 客户端提交账户和登录信息（包含验证码 Captcha）到认证服务 [AuthController.java](file:///home/zeron/dev/cloud-infra/cloud-infra-auth/src/main/java/com/cloudinfra/auth/controller/AuthController.java)。
- 系统的 [SysLoginService.java](file:///home/zeron/dev/cloud-infra/cloud-infra-auth/src/main/java/com/cloudinfra/auth/service/SysLoginService.java) 执行防刷及安全保护：
  1. **验证码校验**：通过 Redis 的 Key (`captcha_codes:uuid`) 获取验证码并比对，验证后即删。
  2. **锁定机制**：检查 Redis 账户锁定 Key (`login_lock:username`)。如果被锁定时，直接拒绝。
  3. **密码鉴权**：调用 Spring Security 的 `AuthenticationManager` 进行底层密码校验。
  4. **失败惩罚**：若密码错误，系统将累计错误计数 (`login_error_count:username`)。当连续 5 次登录失败时，向 Redis 注入锁定标记，锁定账户 10 分钟。
  5. **生成 Token**：登录成功后，清除登录失败计数，调用 [AuthTokenService.java](file:///home/zeron/dev/cloud-infra/cloud-infra-auth/src/main/java/com/cloudinfra/auth/service/AuthTokenService.java) 签发 Token。底层 [JwtUtils.java](file:///home/zeron/dev/cloud-infra/cloud-infra-common/src/main/java/com/cloudinfra/common/util/JwtUtils.java) 利用 JWT 标准（HMAC256 算法）注入 `userId`, `username`, `permissions` 声明并生成 Token 响应。

### 2. 身份传播与网关拦截流程
- 任何非公开客户端请求访问下游服务时，首先经过网关层。
- 网关的核心过滤器 [GlobalAuthFilter.java](file:///home/zeron/dev/cloud-infra/cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/filter/GlobalAuthFilter.java) 执行以下链路生命周期拦截：
  1. **链路追踪 ID（Trace ID）分配**：从报头提取 `traceId`，若不存在则生成一个无中划线的 UUID 并放入日志上下文 `MDC` 中，保证当前日志可以通过 traceId 被串联起来。
  2. **非登录路由校验**：白名单路由（如登录 `/auth/login`）直接放行。
  3. **Token 解析与解密**：非白名单路由拦截 `Authorization` 头，截取 Bearer 后的内容，通过 [JwtUtils.java](file:///home/zeron/dev/cloud-infra/cloud-infra-common/src/main/java/com/cloudinfra/common/util/JwtUtils.java) 进行签名校验并提取用户信息。若 Token 校验失败，直接返回 401 状态。
  4. **下游报头注入（Identity Propagation）**：在重写 Request 的自定义 HttpServletRequest 包装器中，将 `X-User-Id`、`X-User-Name` 及 `traceId` 作为头注入到请求，向下游路由。

### 3. 下游微服务安全过滤器与方法级鉴权流程
- 下游微服务接收到转发的请求，首先会经过其底座中的 [JwtAuthenticationFilter.java](file:///home/zeron/dev/cloud-infra/cloud-infra-framework/framework-security/src/main/java/com/cloudinfra/framework/security/filter/JwtAuthenticationFilter.java)。
- `JwtAuthenticationFilter` 从 `Authorization` 请求头中获取 JWT Token 并进行解析：
  1. 提取出 `username` 和该用户的权限字符列表 `permissions` 声明。
  2. 将每个权限标记转换为 `SimpleGrantedAuthority` 并构建 Spring Security 框架通用的 `UsernamePasswordAuthenticationToken`。
  3. 通过 `SecurityContextHolder.getContext().setAuthentication(auth)` 将当前用户身份及授权列表写入上下文。
- 业务代码层面的 Controller（如系统管理的 [RBACController.java](file:///home/zeron/dev/cloud-infra/cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/RBACController.java) 或 [SysPermissionController.java](file:///home/zeron/dev/cloud-infra/cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysPermissionController.java)）可通过标准的 Spring Security 鉴权注解限制方法访问：
  - 例如 `@PreAuthorize("@ss.hasPermission('system:user:list')")`，此处将调用 [PermissionService.java](file:///home/zeron/dev/cloud-infra/cloud-infra-framework/framework-security/src/main/java/com/cloudinfra/framework/security/service/PermissionService.java) 中的校验机制：
    1. 检查是否存在超级通配权限标记 `*:*:*`；
    2. 检查当前用户角色是否为超级管理员 `admin`；
    3. 检查解析得出的权限列表里是否包含当前接口所需的特定字符（例如 `system:user:list`）。
    4. 任意一条满足即代表校验通过，予以放行；否则抛出拒绝访问异常由全局异常处理器统一转换为 `Result.fail()`。

### 4. 虚拟线程与高并发处理流
- 当下游微服务需要执行异步操作时，依靠 [VirtualThreadConfig.java](file:///home/zeron/dev/cloud-infra/cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/config/VirtualThreadConfig.java) 开启的全局异步线程执行器机制。
- 当 `spring.threads.virtual.enabled=true` 启用时，将注册基于 `Executors.newVirtualThreadPerTaskExecutor()` 构建的 `AsyncTaskExecutor`，使得任何 `@Async` 声明的方法都能以零池化开销、轻量、高吞吐的 Java 虚拟线程处理，完全避免了高频网络 I/O 阻塞造成的系统崩溃。

---

## 四、主要依赖与第三方组件分析 (Main Dependencies)

在 [pom.xml](file:///home/zeron/dev/cloud-infra/pom.xml) 中对系统的整体基础依赖和关键三方组件进行了统一规范，主要包含：

1. **核心框架依赖**：
   - **Spring Boot (v4.0.5)**: 处于 Java 4.x/Spring Boot 4 时代，采用高度现代化的 JDK 25 的微架构基座。
   - **Spring Cloud (v2025.1.1) & Spring Cloud Alibaba (v2025.1.0.0)**: 引入微服务服务发现和全套云原生机制，配置与服务治理全部托管在 **Nacos (nacos:8848)**。
2. **数据访问与存储管理**：
   - **MyBatis-Plus (v3.5.6)**: 提供了敏捷易用的关系型数据库持久化能力和多模块实体映射。
   - **PostgreSQL JDBC (v42.7.2)** & **Flyway (v10.15.0)**: 实现版本化、自动化的数据库迁移和高性能的非阻塞 SQL 查询。
   - **Spring Data Elasticsearch**: 提供极速日志监控、高并发搜索支持。
3. **缓存与分布式锁**：
   - **Redisson (v3.30.0)**: 支持分布式可重入红锁（RedLock）、读写锁，并且结合 Redis 实现了高效分布式流量防护及限流。
4. **安全与认证库**：
   - **Auth0 JWT (v4)** & **JJWT (v0.12.5)**: 负责在网关及系统间生成强签名（HMAC256）、高度抗篡改的用户 Token 票据。
   - **Jasypt (v3.0.5)**: 用于配置文件和环境密钥的加密存储，确保生产环境的数据隐私和安全。
5. **API与文档增强**：
   - **Springdoc-OpenAPI (v2.8.4)** (Knife4j / Swagger Annotations): 自动从 REST 接口生成标准化、实时交互的 OpenAPI 交互文档。
6. **分布式事务与容错**：
   - **Seata (v2.0.0)** & **Resilience4j (v2.2.0)** & **Sentinel (v1.8.9)**: 保证分布式服务链路的可靠性、熔断降级策略和强一致性事务事务补偿。
