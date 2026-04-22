# Spring MVC 虚拟线程网关迁移计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `cloud-infra-gateway` 迁移至 Spring Cloud Gateway MVC，并全面开启虚拟线程支持，彻底移除 WebFlux 依赖。

**Architecture:** 
1. 移除 `spring-cloud-starter-gateway` (WebFlux 栈)。
2. 引入 `spring-cloud-starter-gateway-mvc` (Servlet 栈)。
3. 在全局配置中开启 `spring.threads.virtual.enabled: true`。
4. 清理残留的 `Mono`/`Flux` 及相关响应式类型。

**Tech Stack:** Java 25, Spring Boot 3.4+, Spring Cloud Gateway MVC.

---

### Task 1: 移除 WebFlux 依赖并引入 MVC Gateway

**Files:**
- Modify: `cloud-infra-gateway/pom.xml`

- [ ] **Step 1: 移除 WebFlux 依赖**

```xml
<!-- 移除以下内容 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
```

- [ ] **Step 2: 引入 Gateway MVC 依赖**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway-mvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

- [ ] **Step 3: 运行 Maven 验证**

Run: `mvn clean install -pl cloud-infra-gateway`
Expected: BUILD SUCCESS (若有报错需清理残留 import)

---

### Task 2: 配置全局虚拟线程支持

**Files:**
- Modify: `cloud-infra-gateway/src/main/resources/application.yml`

- [ ] **Step 1: 修改配置**

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

---

### Task 3: 适配 AuthFilter 至 Servlet 规范

**Files:**
- Modify: `cloud-infra-gateway/src/main/java/com/cloudinfra/cloudinfra/gateway/filter/AuthFilter.java`

- [ ] **Step 1: 确认 Servlet Filter 兼容性**

当前 `AuthFilter` 已继承 `OncePerRequestFilter`，无需修改代码逻辑，仅需确保不再使用任何 WebFlux 特有类。

---

### Task 4: 清理全局响应式残留

**Files:**
- Modify: `cloud-infra-gateway/src/main/java/com/cloudinfra/cloudinfra/gateway/` (递归搜索)

- [ ] **Step 1: 确保无 Mono/Flux 引用**

Run: `grep -r "reactor" cloud-infra-gateway/src`
Expected: 无输出

---
