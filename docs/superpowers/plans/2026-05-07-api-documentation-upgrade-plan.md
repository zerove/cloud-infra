# API Documentation Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Annotate `SysUserController` and `SysRoleController` with OpenAPI 3 annotations for production-grade documentation.

**Architecture:** Use SpringDoc OpenAPI (`io.swagger.v3.oas.annotations`) to document controllers and their operations.

**Tech Stack:** Java, Spring Boot 4, SpringDoc OpenAPI 2.8.4.

---

### Task 1: Add OpenApi Dependency to system-module

**Files:**
- Modify: `cloud-infra/cloud-infra-modules/system-module/pom.xml`

- [ ] **Step 1: Add dependency**

```xml
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-modules/system-module/pom.xml
git commit -m "feat: add openapi dependency to system-module"
```

### Task 2: Annotate SysUserController

**Files:**
- Modify: `cloud-infra/cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysUserController.java`

- [ ] **Step 1: Add imports**

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

- [ ] **Step 2: Add Tag annotation to class**

```java
@Tag(name = "User Management", description = "Operations related to system users")
@RestController
@RequestMapping("/system/user")
public class SysUserController {
```

- [ ] **Step 3: Add Operation annotation to list method**

```java
    @Log(title = "用户查询", businessType = "QUERY")
    @GetMapping("/list")
    @Operation(summary = "List users", description = "Retrieve a paginated list of users based on search criteria")
    public Object list(Page<SysUser> page, SysUser user) {
```

- [ ] **Step 4: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysUserController.java
git commit -m "feat: annotate SysUserController for api docs"
```

### Task 3: Annotate SysRoleController

**Files:**
- Modify: `cloud-infra/cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysRoleController.java`

- [ ] **Step 1: Add imports**

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

- [ ] **Step 2: Add Tag annotation to class**

```java
@Tag(name = "Role Management", description = "Operations related to system roles")
@RestController
@RequestMapping("/system/role")
@RequiredArgsConstructor
public class SysRoleController {
```

- [ ] **Step 3: Add Operation annotation to list method**

```java
    @PreAuthorize("@ss.hasPermission('system:role:list')")
    @GetMapping("/list")
    @Operation(summary = "List roles", description = "Retrieve a list of all system roles")
    public Result<List<SysRole>> list() {
```

- [ ] **Step 4: Add Operation annotation to add method**

```java
    @PreAuthorize("@ss.hasPermission('system:role:add')")
    @PostMapping
    @Operation(summary = "Add role", description = "Create a new system role")
    public Result<Void> add(@RequestBody SysRole role) {
```

- [ ] **Step 5: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysRoleController.java
git commit -m "feat: annotate SysRoleController for api docs"
```

### Task 4: Verify

**Files:**
- None

- [ ] **Step 1: Build the project to verify compilation**

Run: `mvn clean install -DskipTests`
Expected: BUILD SUCCESS
