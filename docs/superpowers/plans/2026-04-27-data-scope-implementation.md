# 数据权限控制实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现基于 MyBatis-Plus 拦截器的数据权限过滤 (Data Scope)。

**Architecture:** 自定义注解 `@DataScope` 标记需要过滤的 Mapper 方法，MyBatis 插件拦截器拦截查询 SQL 并动态追加 `WHERE` 条件。

**Tech Stack:** Spring Boot 4.0.6, MyBatis-Plus, JDK 25.

---

### Task 1: 定义数据权限相关注解与枚举

**Files:**
- Create: `cloud-infra-common/src/main/java/com/cloudinfra/common/annotation/DataScope.java`
- Create: `cloud-infra-common/src/main/java/com/cloudinfra/common/enums/DataScopeEnum.java`

- [ ] **Step 1: 创建 DataScope 注解**

```java
package com.cloudinfra.common.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataScope {
    String deptAlias() default "d"; // 部门别名
    String userAlias() default "u"; // 用户别名
}
```

- [ ] **Step 2: 创建 DataScopeEnum 枚举**

```java
package com.cloudinfra.common.enums;

public enum DataScopeEnum {
    ALL, DEPT_AND_CHILD, DEPT, SELF
}
```

- [ ] **Step 3: Commit**

```bash
git add cloud-infra-common/src/main/java/com/cloudinfra/common/annotation/DataScope.java
git add cloud-infra-common/src/main/java/com/cloudinfra/common/enums/DataScopeEnum.java
git commit -m "feat: add DataScope annotation and enum"
```

### Task 2: 实现 MyBatis 拦截器

**Files:**
- Create: `cloud-infra-framework/framework-mybatis/src/main/java/com/cloudinfra/framework/mybatis/interceptor/DataScopeInterceptor.java`

- [ ] **Step 1: 创建 DataScopeInterceptor**

```java
package com.cloudinfra.framework.mybatis.interceptor;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import java.sql.Connection;

@Intercepts({@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})})
public class DataScopeInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 实现 SQL 解析与注入逻辑 (省略具体解析细节，后续填充)
        return invocation.proceed();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-framework/framework-mybatis/src/main/java/com/cloudinfra/framework/mybatis/interceptor/DataScopeInterceptor.java
git commit -m "feat: add DataScopeInterceptor skeleton"
```

### Task 3: 注册拦截器并集成

**Files:**
- Modify: `cloud-infra-framework/framework-mybatis/src/main/java/com/cloudinfra/framework/mybatis/config/MyBatisConfig.java`

- [ ] **Step 1: 在 MyBatisConfig 中添加 Bean**

```java
@Bean
public DataScopeInterceptor dataScopeInterceptor() {
    return new DataScopeInterceptor();
}
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-framework/framework-mybatis/src/main/java/com/cloudinfra/framework/mybatis/config/MyBatisConfig.java
git commit -m "feat: register DataScopeInterceptor in MyBatisConfig"
```
