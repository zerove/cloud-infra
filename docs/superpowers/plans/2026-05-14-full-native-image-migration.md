# 全模块原生镜像化 (GraalVM Native Image) 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将全项目所有微服务模块迁移至 GraalVM Native Image 架构，实现毫秒级启动和极低内存占用。

**Architecture:** 基于 Spring Boot 4 AOT 引擎，在框架层实现自动化的 Runtime Hints 注册逻辑，适配 MyBatis Plus 和 Redisson，并采用三阶段 Docker 构建方案。

**Tech Stack:** GraalVM 25, Spring Boot 4.0.5, Spring Cloud 2025, MyBatis Plus 3.5.6, Redisson 3.30.0, OpenTelemetry.

---

### Task 1: 根项目 Maven 配置升级 (M1)

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 添加 native-maven-plugin 依赖管理**

在根 `pom.xml` 的 `<build><pluginManagement><plugins>` 中添加：
```xml
<plugin>
    <groupId>org.graalvm.buildtools</groupId>
    <artifactId>native-maven-plugin</artifactId>
    <version>0.10.4</version>
    <extensions>true</extensions>
    <executions>
        <execution>
            <id>build-native</id>
            <goals>
                <goal>compile-no-fork</goal>
            </goals>
            <phase>package</phase>
        </execution>
        <execution>
            <id>test-native</id>
            <goals>
                <goal>test</goal>
            </goals>
            <phase>test</phase>
        </execution>
    </executions>
    <configuration>
        <fallback>false</fallback>
        <buildArgs>
            <arg>-H:+ReportExceptionStackTraces</arg>
            <arg>--no-fallback</arg>
            <arg>--enable-http</arg>
            <arg>--enable-https</arg>
        </buildArgs>
    </configuration>
</plugin>
```

- [ ] **Step 2: 验证 pom.xml 格式**

Run: `mvn help:effective-pom -DskipTests`
Expected: 成功输出，无 XML 解析错误。

- [ ] **Step 3: 提交记录**

```bash
git add pom.xml
git commit -m "chore(build): add native-maven-plugin to root pom"
```

---

### Task 2: 框架层 AOT 适配器实现 (M2)

**Files:**
- Create: `cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/config/CloudInfraRuntimeHints.java`
- Modify: `cloud-infra-framework/framework-web/src/main/resources/META-INF/spring/aot.factories`

- [ ] **Step 1: 创建 RuntimeHints 注册器**

```java
package com.cloudinfra.framework.web.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CloudInfraRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 注册通用的 Result 模型
        hints.reflection().registerType(com.cloudinfra.common.core.domain.Result.class, 
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, 
            MemberCategory.INVOKE_DECLARED_METHODS, 
            MemberCategory.DECLARED_FIELDS);
        
        // 自动注册 Entity 和 Mapper 的逻辑将在后续 Task 5 中通过类路径扫描实现
        // 此处先注册框架核心类
    }
}
```

- [ ] **Step 2: 注册 AOT 工厂文件**

创建 `cloud-infra-framework/framework-web/src/main/resources/META-INF/spring/aot.factories`：
```properties
org.springframework.aot.hint.RuntimeHintsRegistrar=com.cloudinfra.framework.web.config.CloudInfraRuntimeHints
```

- [ ] **Step 3: 提交记录**

```bash
git add cloud-infra-framework/framework-web/
git commit -m "feat(framework): implement base RuntimeHintsRegistrar"
```

---

### Task 3: 网关模块 (Gateway) 原生迁移 (M3)

**Files:**
- Modify: `cloud-infra-gateway/pom.xml`

- [ ] **Step 1: 启用 native profile**

在 `cloud-infra-gateway/pom.xml` 中引入插件：
```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.graalvm.buildtools</groupId>
            <artifactId>native-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

- [ ] **Step 2: 模拟 AOT 预处理**

运行 Spring Boot 的 AOT 预处理（无需 GraalVM 环境也可执行）：
Run: `mvn spring-boot:process-aot`
Expected: 在 `target/spring-aot` 下生成源代码。

- [ ] **Step 3: 提交记录**

```bash
git add cloud-infra-gateway/pom.xml
git commit -m "feat(gateway): enable native image support"
```

---

### Task 4: 业务模块自动化 AOT 扫描器 (M4)

**Files:**
- Modify: `cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/config/CloudInfraRuntimeHints.java`

- [ ] **Step 1: 实现自动类路径扫描注册**

```java
// 在 CloudInfraRuntimeHints.java 中增加扫描逻辑
// 使用 ClassPathScanningCandidateComponentProvider 扫描 com.cloudinfra 下的特定包
```

- [ ] **Step 2: 提交记录**

```bash
git add cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/config/CloudInfraRuntimeHints.java
git commit -m "feat(framework): add automatic classpath scanning for AOT hints"
```

---

### Task 5: 容器化策略更新 (Dockerfile.native)

**Files:**
- Create: `deploy/Dockerfile.native`
- Modify: `deploy/compose.yaml`

- [ ] **Step 1: 编写 Native Dockerfile**

```dockerfile
# 阶段 1: 构建原生二进制
FROM ghcr.io/graalvm/native-image-community:25 AS builder
WORKDIR /app
COPY . .
RUN ./mvnw native:compile -Pnative -pl cloud-infra-gateway -am

# 阶段 2: 运行
FROM debian:bookworm-slim
COPY --from=builder /app/cloud-infra-gateway/target/cloud-infra-gateway /app/gateway
ENTRYPOINT ["/app/gateway"]
```

- [ ] **Step 2: 提交记录**

```bash
git add deploy/Dockerfile.native
git commit -m "chore(deploy): add native dockerfile template"
```

---

### Task 6: 可观测性 OTLP 适配

**Files:**
- Modify: `cloud-infra-framework/framework-tracing/pom.xml`
- Modify: `configs/gateway.yaml` (示例)

- [ ] **Step 1: 引入 OTLP Exporter**

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

- [ ] **Step 2: 提交记录**

```bash
git add cloud-infra-framework/framework-tracing/pom.xml
git commit -m "feat(tracing): add OTLP exporter for native compatibility"
```
