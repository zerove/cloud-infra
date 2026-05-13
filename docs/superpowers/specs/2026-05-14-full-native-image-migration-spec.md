# 全模块原生镜像化 (GraalVM Native Image) 迁移设计文档

## 1. 目标与背景
本项目基于 **Spring Boot 4.0.5** 和 **JDK 25**，旨在通过 **GraalVM Native Image** 技术将所有微服务模块转换为原生二进制文件。

### 核心价值
- **极致启动速度**：从秒级缩短至毫秒级（约 50ms - 200ms）。
- **极低内存占用**：运行时内存占用降低 60%-80%，单服务基准内存期望在 64MB-128MB。
- **云原生亲和力**：镜像体积大幅缩小，无 JVM 热身成本，完美适配 Serverless 或高频扩缩容场景。

---

## 2. 总体架构设计

### 2.1 技术栈演进
| 维度 | 当前方案 (JVM) | 目标方案 (Native) |
| :--- | :--- | :--- |
| **运行时** | OpenJDK 25 (JRE) | GraalVM Native Image (Substrate VM) |
| **基础镜像** | eclipse-temurin:25-jre-alpine | gcr.io/distroless/static-debian12 (或 alpine) |
| **配置注入** | 动态反射 + CGLIB | Spring AOT 预处理 + Runtime Hints |
| **可观测性** | Micrometer + Java Agent | Spring Native OTEL Exporter (OTLP) |
| **并发模型** | 虚拟线程 (JDK 25) | 虚拟线程 (Substrate VM 优化版) |

### 2.2 核心适配策略：自动 Runtime Hints
为了解决 MyBatis Plus、Redisson 等框架的反射问题，将在 `cloud-infra-framework` 中实现 `CloudInfraRuntimeHintsRegistrar`。

#### 自动注册范围：
- **MyBatis Plus**: 自动扫描 `com.cloudinfra.**.entity.**` 下的所有实体类及 `com.cloudinfra.**.mapper.**` 下的接口。
- **Redisson**: 自动注册 Redis 序列化所需的 DTO 和 POJO。
- **Jackson**: 自动注册所有的 API 返回值 (Result) 和请求参数 (DTO)。

---

## 3. 详细实施方案

### 3.1 框架层适配 (Framework Module)
在 `cloud-infra-framework-web` 中引入 AOT 自动注册逻辑：
```java
// 伪代码示例
public class CloudInfraRuntimeHintsRegistrar implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 1. 扫描所有的 Entity，注册反射权限
        registerReflectionForPattern(hints, "com.cloudinfra.**.entity.**");
        // 2. 扫描 Mapper，注册 JDK 代理权限
        registerProxyForPattern(hints, "com.cloudinfra.**.mapper.**");
        // 3. 注册通用的序列化/反序列化类型
        hints.reflection().registerType(CommonResult.class, MemberCategory.DECLARED_FIELDS, ...);
    }
}
```

### 3.2 数据库与持久层 (MyBatis Plus)
- **静态分析替换动态解析**：利用 Spring Boot 4 的 AOT 引擎，在构建期生成 MyBatis 的 Mapper 代理类。
- **配置冻结**：注意 MyBatis 的 XML 映射文件必须在构建期确定，不支持运行期动态加载外部磁盘上的 XML。

### 3.3 容器化策略 (Dockerfile)
采用三阶段构建以优化缓存和镜像体积：
1. **Maven Build**: 标准 jar 包构建。
2. **Native Compile**: 使用 `native-image-community:25` 进行 AOT 编译（分配 8G+ 内存）。
3. **Final Run**: 仅包含二进制文件和必要的 C 运行时库（`libz`, `glibc`/`musl`）。

---

## 4. 可观测性升级
原生镜像不支持大多数基于字节码修改的 Java Agent (如 SkyWalking Agent)。
- **迁移方案**：使用 **Micrometer Tracing** 的内置 OTLP Exporter。
- **数据流**：服务端点 $\rightarrow$ OTLP (HTTP) $\rightarrow$ OpenTelemetry Collector $\rightarrow$ Tempo/Jaeger/Prometheus。

---

## 5. 实施路线图 (Milestones)
1. **[M1] 基础设施就绪**：升级根 POM，引入 `native-maven-plugin`，配置 GitHub Actions / 本地构建脚本。
2. **[M2] 框架层 AOT 适配**：实现 `RuntimeHintsRegistrar`，完成对 MyBatis 和 Redisson 的基础支持。
3. **[M3] 网关与认证迁移**：Gateway 和 Auth 服务原生镜像化，验证路由和 JWT 校验逻辑。
4. **[M4] 业务模块全量迁移**：System, Notification, Search 等模块逐一适配，处理特殊的反射场景。
5. **[M5] 性能压测与调优**：对比 JVM vs Native 在虚拟线程下的吞吐量和延迟，优化内存限制。

---

## 6. 风险与规避
- **构建时间增加**：原生编译可能耗时 5-10 分钟。规避：开发环境继续使用 JVM 模式，仅 CI/CD 环境启用 Native。
- **第三方库兼容性**：部分老旧库可能不支持 AOT。规避：使用 `agent-lib` 在 JVM 模式下运行并自动生成配置文件。
- **序列化问题**：忘记注册 DTO 会导致 API 返回空值。规避：通过框架层自动扫描 `cloud-infra-api` 模块下的所有类。
