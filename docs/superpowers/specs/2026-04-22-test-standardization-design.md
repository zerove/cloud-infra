# 测试环境标准化设计规范

**Goal:** 构建统一、高性能、高可维护的集成测试体系。

**Architecture:**
1. **基础层：** 在 `cloud-infra-common` 模块中定义测试依赖版本管理与公共基类 (`BaseIntegrationTest`)。
2. **执行层：** 配置 `junit-platform.properties` 以开启 JUnit 5 并行执行，优化虚拟线程支持。
3. **隔离层：** 强制使用 `Testcontainers` 替代硬编码的本地 Redis/MySQL 连接，确保环境幂等。

**Tech Stack:** Java 25, JUnit 5, AssertJ, Mockito, Testcontainers.

---

### 主要实施组件

#### 1. 全局测试配置
在项目根目录创建 `src/test/resources/junit-platform.properties`，开启虚拟线程并行测试：
```properties
junit.jupiter.execution.parallel.enabled = true
junit.jupiter.execution.parallel.mode.default = concurrent
```

#### 2. 公共测试基类 (cloud-infra-common)
实现 `com.cloudinfra.common.test.BaseIntegrationTest`，提供：
- `@SpringBootTest` 默认配置
- 自动引入 `Testcontainers` 的 Redis 容器上下文

#### 3. 规范化 Mock 与断言
- 强制 JUnit 5 AssertJ 模式：`assertThat(actual).isEqualTo(expected)`。
- 所有 Mock 服务强制使用 `@MockBean` 并在每个测试用例后重置。

---

**已完成脑暴，准备实施。是否批准该设计规范？**
