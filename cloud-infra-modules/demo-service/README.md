# Demo Service

> **Cloud-Infra 平台最小可运行示例模块**
> 
> 用作新业务模块的脚手架模板。复制此目录，修改包名和业务逻辑即可快速创建符合平台规范的微服务。

---

## 🎯 设计目标

- **零配置启动**：引入 `cloud-infra-starter` 即可自动装配全平台能力
- **标准化结构**：遵循分层架构，Controller/Service/Mapper 分离
- **可观测性内置**：Actuator 管理端口、Prometheus 指标、链路追踪
- **安全开箱即用**：JWT 认证、RBAC 鉴权、幂等保护
- **测试自包含**：Testcontainers 启动 Redis，无外部依赖

---

## 📁 目录结构

```
demo-service/
├── pom.xml                              # Maven 配置，父模块 cloud-infra-modules
├── src/
│   ├── main/
│   │   ├── java/com/cloudinfra/demo/
│   │   │   ├── DemoApplication.java     # 启动类，扫描 com.cloudinfra 包
│   │   │   └── controller/
│   │   │       └── HealthController.java # 健康检查 + 身份回显端点
│   │   └── resources/
│   │       └── application.yml           # 标准配置（数据源、Nacos、Actuator、Outbox）
│   └── test/
│       ├── java/com/cloudinfra/demo/
│       │   └── HealthControllerTest.java # 冒烟测试，Testcontainers 自带 Redis
│       └── resources/
│           └── application.yml           # 测试配置（H2、禁用 Nacos/Redis 自动配置）
├── Dockerfile                            # 多阶段构建：Maven 编译 + JRE 运行
└── deploy/k8s/values.yaml                # K8s 部署参数
```

---

## 🚀 快速开始

### 1. 复制为新模块

```bash
# 在 cloud-infra-modules 目录下
cp -r demo-service your-new-service
cd your-new-service

# 批量重命名包名
find . -name "*.java" -exec sed -i 's/com.cloudinfra.demo/com.yourcompany.yourservice/g' {} +
find . -name "*.java" -exec sed -i 's/DemoApplication/YourServiceApplication/g' {} +
# 同步修改目录结构
mv src/main/java/com/cloudinfra/demo src/main/java/com/yourcompany/yourservice
mv src/test/java/com/cloudinfra/demo src/test/java/com/yourcompany/yourservice
```

### 2. 修改 pom.xml

```xml
<artifactId>your-new-service</artifactId>
<properties>
    <main.class>com.yourcompany.yourservice.YourServiceApplication</main.class>
</properties>
```

### 3. 修改 application.yml

```yaml
spring:
  application:
    name: your-new-service
  # 数据源、Nacos、Actuator 配置按需调整
```

### 4. 父模块注册

在 `cloud-infra-modules/pom.xml` 的 `<modules>` 中添加：

```xml
<module>your-new-service</module>
```

---

## 🔧 核心能力演示

| 能力 | 代码位置 | 说明 |
| :--- | :--- | :--- |
| **平台自动装配** | `DemoApplication.java` | `@EnableDiscoveryClient`、`@EnableFeignClients`、`@EnableTransactionManagement` |
| **身份传播** | `HealthController.java` | `PlatformSecurityContext.current()` 获取当前用户 |
| **健康检查** | `HealthController.java` | `GET /api/health` 返回服务状态、认证状态 |
| **Outbox 事件** | `application.yml` | `cloud-infra.outbox.enabled=true` 事务发件箱 |
| **虚拟线程** | `application.yml` | `spring.threads.virtual.enabled=true`（平台统一开启） |
| **链路追踪** | `application.yml` | OTLP 导出到 `otel-collector:4317` |

---

## 🧪 测试

```bash
# 单模块测试（自动启动 Redis 容器）
cd cloud-infra-modules/demo-service
mvn test

# 或从根目录
mvn test -pl cloud-infra-modules/demo-service
```

**测试特点**：
- `@Testcontainers` 自动管理 Redis 容器生命周期
- `@DynamicPropertySource` 注入动态端口
- H2 内存数据库，无外部依赖
- 禁用 Nacos 配置/发现，纯单元测试模式

---

## 🐳 本地构建与运行

```bash
# 构建 JAR
mvn clean package -pl cloud-infra-modules/demo-service -am -DskipTests

# 运行（需先启动基础设施：./deploy/up.sh）
java -jar target/demo-service-1.0.0-SNAPSHOT.jar

# 或使用 Docker
podman build -t demo-service:latest .
podman run -p 8099:8099 demo-service:latest
```

**验证端点**：
- 健康检查：`curl http://localhost:8099/api/health`
- 身份回显：`curl -H "Authorization: Bearer <token>" http://localhost:8099/api/health/whoami`
- Actuator：`http://localhost:9301/management/health`

---

## 📦 K8s 部署

```bash
# 使用 Helm values
helm install demo-service ./deploy/k8s -f deploy/k8s/values.yaml
```

关键配置（`deploy/k8s/values.yaml`）：
- 副本数：2
- 资源：CPU 500m-1000m，内存 256Mi-512Mi
- 端口：8099（业务）、9301（管理）

---

## 📋 新模块清单（复制后必改项）

| 文件 | 修改项 |
| :--- | :--- |
| `pom.xml` | `artifactId`、`main.class`、依赖按需增减 |
| `DemoApplication.java` | 类名、包名、`@SpringBootApplication(scanBasePackages)` |
| `application.yml` | `spring.application.name`、数据源、Nacos data-id |
| `HealthController.java` | 业务端点替换、权限注解 `@PreAuthorize` |
| `Dockerfile` | 基础镜像版本、JVM 参数 |
| `deploy/k8s/values.yaml` | 镜像名、资源限制、环境变量 |

---

## 📚 参考文档

- [开发者指南](../DEVELOPER_GUIDE.md) - 创建新模块标准流程
- [架构文档](../ARCHITECTURE.md) - 分层、依赖、关键决策
- [部署指南](../DEPLOYMENT.md) - Native/JRE 混合构建、K8s 部署
- [API 概览](../API_OVERVIEW.md) - 网关路由、服务端口映射

---

## ⚠️ 注意事项

1. **不要直接修改 demo-service 实现业务**，应复制后重命名
2. **测试环境隔离**：测试配置禁用了 Nacos/Redis 自动配置，生产配置在 `application.yml` 与 Nacos 中
3. **依赖最小化**：仅引入 `cloud-infra-starter`、`cloud-infra-common`、`cloud-infra-api`，按需添加 framework-* 模块
4. **安全基线**：生产环境必须配置 `AUTH_JWT_SECRET`（≥32 字符），不要使用示例值

---

*此模块随平台演进持续更新，保持为最佳实践参考实现。*