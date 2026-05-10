# Data Scope Logic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the implementation of the Data Scope mechanism, including the `DataScopeInterceptor` and its integration with service-layer queries.

**Architecture:** Use a MyBatis interceptor to detect `@DataScope` annotations on mapper methods and dynamically append SQL fragments for filtering based on the user's data scope (e.g., department access).

**Tech Stack:** Java, Spring Boot, MyBatis.

---

### Task 1: Create DataScopeInterceptor

**Files:**
- Create: `cloud-infra-framework/framework-mybatis/src/main/java/com/cloudinfra/framework/mybatis/interceptor/DataScopeInterceptor.java`

- [ ] **Step 1: Write skeleton Interceptor**

```java
package com.cloudinfra.framework.mybatis.interceptor;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import java.sql.Connection;
import java.util.Properties;

@Intercepts({@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})})
public class DataScopeInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // Logic to be implemented in Task 2
        return invocation.proceed();
    }
}
```

- [ ] **Step 2: Commit**
`git add cloud-infra-framework/framework-mybatis/src/main/java/com/cloudinfra/framework/mybatis/interceptor/DataScopeInterceptor.java`
`git commit -m "feat: add DataScopeInterceptor skeleton"`

### Task 2: Implement DataScope Logic

**Files:**
- Modify: `cloud-infra-framework/framework-mybatis/src/main/java/com/cloudinfra/framework/mybatis/interceptor/DataScopeInterceptor.java`

- [ ] **Step 1: Implement SQL interception logic**

(Placeholder for actual implementation: parse SQL, detect @DataScope, append SQL).

- [ ] **Step 2: Commit**
`git commit -am "feat: implement DataScope SQL injection logic"`

### Task 3: Integrate with System Service

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysDeptServiceImpl.java`

- [ ] **Step 1: Apply @DataScope to select methods**

- [ ] **Step 2: Commit**
`git commit -am "feat: apply @DataScope to SysDeptService"`
