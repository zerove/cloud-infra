# 权限认证系统实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现基于 JWT + Redis 黑名单的分布式鉴权机制。

**Architecture:** 扩展 `framework-security` 实现带有 `jti` 的 Token 生成与校验，并在 `cloud-infra-gateway` 中增加全局 Filter 进行黑名单验证。

**Tech Stack:** Spring Security (impl), JWT, Redis.

---

### Task 1: 扩展 JwtUtils 实现 JTI 支持
**Files:**
- Modify: `cloud-infra-framework/framework-security/src/main/java/com/cloudinfra/framework/security/utils/JwtUtils.java`

- [ ] **Step 1: 修改 JwtUtils 添加 JTI 支持**

```java
// 在 JwtUtils 中增加 jti 生成和读取
public static String generateToken(String username) {
    String jti = java.util.UUID.randomUUID().toString();
    Map<String, Object> claims = new HashMap<>();
    claims.put("jti", jti);
    return createToken(claims, username);
}

public static String getJtiFromToken(String token) {
    return parseToken(token).get("jti", String.class);
}
```

### Task 2: 实现 RedisService 黑名单能力
**Files:**
- Modify: `cloud-infra-framework/framework-redis/src/main/java/com/cloudinfra/framework/redis/service/RedisService.java` (假设存在此服务)

- [ ] **Step 1: 添加黑名单方法**

```java
public void addToBlacklist(String jti, long expireTime) {
    redisTemplate.opsForValue().set("token:blacklist:" + jti, "1", expireTime, TimeUnit.MILLISECONDS);
}

public boolean isBlacklisted(String jti) {
    return Boolean.TRUE.equals(redisTemplate.hasKey("token:blacklist:" + jti));
}
```

### Task 3: 在 Gateway 实现全局鉴权 Filter
**Files:**
- Create: `cloud-infra-gateway/src/main/java/com/cloudinfra/cloudinfra/gateway/filter/AuthFilter.java`

- [ ] **Step 1: 实现 AuthFilter**

```java
@Component
public class AuthFilter implements GlobalFilter, Ordered {
    // 拦截 Authorization 头，校验 JWT，检查 Redis 黑名单，将用户 ID 放入 Header 透传
}
```

### Task 4: 配置透传与异常响应
**Files:**
- Modify: `cloud-infra-gateway/src/main/resources/application.yml`

- [ ] **Step 1: 配置全局过滤器优先级与白名单逻辑**

---

**Plan complete and saved to `docs/superpowers/plans/2026-04-21-security-auth-implementation.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
