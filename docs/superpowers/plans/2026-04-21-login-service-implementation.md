# 登录认证服务实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `cloud-infra-auth` 实现登录接口，对接 `system-module` 用户验证。

**Architecture:** 定义 UserFeignClient，在 AuthService 实现登录逻辑。

**Tech Stack:** Spring Cloud OpenFeign, Spring Security BCrypt.

---

### Task 1: 定义 OpenFeign Client 接口
**Files:**
- Create: `cloud-infra-auth/src/main/java/com/cloudinfra/cloudinfra/auth/feign/UserFeignClient.java`

- [ ] **Step 1: 创建 FeignClient**

```java
@FeignClient(name = "system-module", contextId = "userClient")
public interface UserFeignClient {
    @GetMapping("/users/username/{username}")
    UserDTO getByUsername(@PathVariable("username") String username);
}
```

### Task 2: 实现登录 Service 层逻辑
**Files:**
- Create: `cloud-infra-auth/src/main/java/com/cloudinfra/cloudinfra/auth/service/AuthService.java`

- [ ] **Step 1: 实现登录逻辑**

```java
@Service
public class AuthService {
    @Resource private UserFeignClient userFeignClient;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthResponse login(String username, String password) {
        UserDTO user = userFeignClient.getByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }
        String token = JwtUtils.generateToken(username);
        return new AuthResponse(token, new UserInfo(user.getId(), user.getUsername()));
    }
}
```

### Task 3: 实现登录 Controller
**Files:**
- Create: `cloud-infra-auth/src/main/java/com/cloudinfra/cloudinfra/auth/controller/AuthController.java`

- [ ] **Step 1: 添加登录接口**

```java
@RestController
@RequestMapping("/auth")
public class AuthController {
    @Resource private AuthService authService;

    @PostMapping("/login")
    public Result login(@RequestBody LoginRequest req) {
        return Result.success(authService.login(req.getUsername(), req.getPassword()));
    }
}
```

---

**Plan complete and saved to `docs/superpowers/plans/2026-04-21-login-service-implementation.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
