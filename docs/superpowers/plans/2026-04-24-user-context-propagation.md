# User Context Propagation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement context propagation from Gateway to business modules via custom Headers.

**Architecture:**
1. **Common**: Add UserContext and Interceptor.
2. **Gateway**: Inject Headers in GlobalAuthFilter.
3. **Business**: Configure Interceptor to consume headers.

**Tech Stack:** Spring Boot, ThreadLocal, Spring WebMvc

---

### Task 1: Create UserContext in cloud-infra-common

**Files:**
- Create: `cloud-infra-common/src/main/java/com/cloudinfra/common/context/UserContext.java`

- [ ] **Step 1: Create UserContext.java**
```java
package com.cloudinfra.common.context;

import java.util.HashMap;
import java.util.Map;

public class UserContext {
    private static final ThreadLocal<Map<String, String>> CONTEXT = new ThreadLocal<>();

    public static void set(String key, String value) {
        if (CONTEXT.get() == null) {
            CONTEXT.set(new HashMap<>());
        }
        CONTEXT.get().put(key, value);
    }

    public static String get(String key) {
        return CONTEXT.get() != null ? CONTEXT.get().get(key) : null;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
```

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-common/src/main/java/com/cloudinfra/common/context/UserContext.java
git commit -m "feat: add UserContext to common module"
```

### Task 2: Inject Headers in Gateway

**Files:**
- Modify: `cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/filter/GlobalAuthFilter.java`

- [ ] **Step 1: Update GlobalAuthFilter to set X-User-Id and X-User-Name**
```java
// Add in doFilterInternal after successful JWT parse
DecodedJWT jwt = JwtUtils.parseToken(token.replace("Bearer ", ""));
String userId = jwt.getClaim("userId").asString();
String username = jwt.getSubject();

HttpServletRequestWrapper requestWrapper = new HttpServletRequestWrapper(request) {
    @Override
    public String getHeader(String name) {
        if ("X-User-Id".equals(name)) return userId;
        if ("X-User-Name".equals(name)) return username;
        return super.getHeader(name);
    }
};
filterChain.doFilter(requestWrapper, response);
```

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/filter/GlobalAuthFilter.java
git commit -m "feat: inject user context headers in gateway"
```

### Task 3: Implement WebMvcInterceptor in system-module

**Files:**
- Create: `system-module/src/main/java/com/cloudinfra/system/config/UserContextInterceptor.java`
- Modify: `system-module/src/main/java/com/cloudinfra/system/config/WebMvcConfig.java`

- [ ] **Step 1: Create UserContextInterceptor**
```java
package com.cloudinfra.system.config;

import com.cloudinfra.common.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class UserContextInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserContext.set("userId", request.getHeader("X-User-Id"));
        UserContext.set("username", request.getHeader("X-User-Name"));
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
```

- [ ] **Step 2: Register Interceptor**
```java
// In WebMvcConfig
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserContextInterceptor());
    }
}
```

- [ ] **Step 3: Commit**
```bash
git add system-module/src/main/java/com/cloudinfra/system/config/
git commit -m "feat: add UserContextInterceptor to system module"
```
