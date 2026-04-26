# 测试基础架构标准化实施计划 (v2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立统一、隔离且高性能的集成测试体系，通过 `cloud-infra-test-support` 模块标准化测试环境。

**Architecture:**
1. **统一入口**：将所有通用测试基类和工具迁移至 `cloud-infra-test-support` 模块。
2. **环境隔离**：利用 `Testcontainers` 自动管理 Redis 和 MySQL 容器，消除对本地中间件的依赖。
3. **并发优化**：配置 JUnit 5 并行执行模式，利用 Java 25 虚拟线程提升测试速度。

**Tech Stack:** Java 25, JUnit 5, Testcontainers, Spring Boot Test, AssertJ.

---

### Task 1: 规范化 cloud-infra-test-support 模块

**Files:**
- Modify: `cloud-infra-test-support/pom.xml`
- Create: `cloud-infra-test-support/src/main/java/com/cloudinfra/test/BaseIntegrationTest.java`
- Delete: `cloud-infra-test-support/src/test/java/com/cloudinfra/test/BaseIntegrationTest.java`
- Delete: `cloud-infra-common/src/test/java/com/cloudinfra/common/test/BaseIntegrationTest.java`

- [ ] **Step 1: 更新模块 pom.xml**
添加必要的测试容器依赖。

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>mysql</artifactId>
    </dependency>
    <dependency>
        <groupId>org.redisson</groupId>
        <artifactId>redisson-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

- [ ] **Step 2: 创建标准的 BaseIntegrationTest**
将基类移至 `src/main/java` 以便其他模块引用。

```java
package com.cloudinfra.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class BaseIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.0")
            .withDatabaseName("cloud_infra")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
```

- [ ] **Step 3: 清理旧的基类**
删除 `cloud-infra-common` 和 `test-support/src/test` 下的重复基类。

- [ ] **Step 4: 编译验证**
运行 `mvn clean compile -pl cloud-infra-test-support`。

---

### Task 2: 根 POM 依赖标准化

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 统一测试依赖版本**
在 `dependencyManagement` 中确保 `testcontainers` 版本一致。

```xml
<properties>
    <testcontainers.version>1.20.1</testcontainers.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-bom</artifactId>
            <version>${testcontainers.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

### Task 3: 开启全局并行测试配置

**Files:**
- Create: `src/test/resources/junit-platform.properties`

- [ ] **Step 1: 写入配置**

```properties
junit.jupiter.execution.parallel.enabled = true
junit.jupiter.execution.parallel.mode.default = concurrent
junit.jupiter.execution.parallel.mode.classes.default = concurrent
```

---

### Task 4: 改造 Auth 模块集成测试

**Files:**
- Modify: `cloud-infra-auth/pom.xml`
- Create: `cloud-infra-auth/src/test/java/com/cloudinfra/auth/AuthIntegrationTest.java`

- [ ] **Step 1: 添加 test-support 依赖**

```xml
<dependency>
    <groupId>com.cloudinfra</groupId>
    <artifactId>cloud-infra-test-support</artifactId>
    <version>${revision}</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: 编写集成测试验证容器启动**

```java
package com.cloudinfra.auth;

import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class AuthIntegrationTest extends BaseIntegrationTest {
    @Test
    void contextLoads() {
        assertThat(mysql.isRunning()).isTrue();
        assertThat(redis.isRunning()).isTrue();
    }
}
```

- [ ] **Step 3: 运行测试**
运行 `mvn test -pl cloud-infra-auth -Dtest=AuthIntegrationTest`。

---
