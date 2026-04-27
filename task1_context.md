# Task 1: Create DataScopeInterceptor

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
