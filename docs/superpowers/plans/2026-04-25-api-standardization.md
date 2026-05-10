# API Standardization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standardize Controller responses using Result<T> and refine Exception handling.

**Architecture:**
1. **Enum**: Extend `ResultCode` with standard business codes.
2. **Controller**: Refactor `AuthController` to return `Result<T>`.

**Tech Stack:** Spring Boot, Result<T> pattern

---

### Task 1: Extend ResultCode
**Files:**
- Modify: `cloud-infra-common/src/main/java/com/cloudinfra/common/core/enums/ResultCode.java`

- [ ] **Step 1: Add new business error codes**
```java
public enum ResultCode {
    SUCCESS(200, "操作成功"),
    ERROR(500, "系统内部错误"),
    BUSINESS_ERROR(400, "业务异常"),
    AUTH_EXPIRED(401, "登录已过期"),
    USER_NOT_FOUND(404, "用户不存在");
    
    private final Integer code;
    private final String msg;
    ResultCode(Integer code, String msg) { this.code = code; this.msg = msg; }
    public Integer getCode() { return code; }
    public String getMsg() { return msg; }
}
```

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-common/src/main/java/com/cloudinfra/common/core/enums/ResultCode.java
git commit -m "feat: extend ResultCode with business error codes"
```

### Task 2: Refactor AuthController
**Files:**
- Modify: `cloud-infra-auth/src/main/java/com/cloudinfra/auth/controller/AuthController.java`

- [ ] **Step 1: Refactor to return Result<Map<String, Object>>**
```java
@PostMapping("/login")
public Result<Map<String, Object>> login(@RequestBody LoginUser loginUser) {
    return Result.success(authTokenService.createToken(loginUser));
}

@PostMapping("/refresh")
public Result<Map<String, Object>> refresh(@RequestHeader("Refresh-Token") String refreshToken) {
    return Result.success(authTokenService.refreshToken(refreshToken));
}
```

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-auth/src/main/java/com/cloudinfra/auth/controller/AuthController.java
git commit -m "refactor: standardize AuthController response to Result"
```
