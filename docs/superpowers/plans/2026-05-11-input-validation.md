# 输入校验增强实现计划 (Jakarta Validation)

> **面向智能体工作者**: 必须使用的子技能: 建议使用 `superpowers:subagent-driven-development` 或 `superpowers:executing-plans` 逐项执行此计划。步骤使用复选框 (`- [ ]`) 语法进行跟踪。

**目标**: 引入 Jakarta Validation 机制，实现对请求参数的声明式校验，并确保校验失败时返回统一的错误响应格式。

**架构**: 
1. 在根 `pom.xml` 中添加 `spring-boot-starter-validation` 依赖管理。
2. 在 `cloud-infra-common` 中引入该依赖。
3. 升级 `GlobalExceptionHandler` 以拦截 `MethodArgumentNotValidException` (针对 `@RequestBody`) 和 `ConstraintViolationException` (针对路径参数/查询参数)。
4. 为核心 DTO 添加校验注解。

**技术栈**: Spring Boot 4, Jakarta Validation API, Hibernate Validator.

---

## 文件结构映射

- **修改**: `pom.xml` (根目录) - 添加依赖管理
- **修改**: `cloud-infra-common/pom.xml` - 引入 validation starter
- **修改**: `cloud-infra-common/src/main/java/com/cloudinfra/common/core/exception/GlobalExceptionHandler.java` - 增加校验异常处理逻辑
- **修改**: `cloud-infra-api/src/main/java/com/cloudinfra/api/dto/` (选取代表性 DTO) - 添加校验注解
- **创建**: `cloud-infra-common/src/test/java/com/cloudinfra/common/core/exception/ValidationExceptionHandlerTest.java` - 验证校验失败的响应格式

---

## 详细执行步骤

### 任务 1: 依赖集成

- [ ] **步骤 1.1: 更新根 `pom.xml`**
  在 `<dependencyManagement>` 的 `<dependencies>` 列表中添加 `spring-boot-starter-validation`。由于使用 Spring Boot BOM，无需指定版本。

- [ ] **步骤 1.2: 更新 `cloud-infra-common/pom.xml`**
  在 `<dependencies>` 中添加 `spring-boot-starter-validation` 依赖。

- [ ] **步骤 1.3: 提交代码**
  `git add pom.xml cloud-infra-common/pom.xml`
  `git commit -m "build(common): add spring-boot-starter-validation dependency"`

### 任务 2: 增强异常处理器

- [ ] **步骤 2.1: 修改 `GlobalExceptionHandler`**
  在 `com.cloudinfra.common.core.exception.GlobalExceptionHandler` 中添加以下处理方法：

```java
    /**
     * 处理 @RequestBody 参数校验异常
     */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMethodArgumentNotValidException(org.springframework.web.bind.MethodArgumentNotValidException e) {
        String errorMessage = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(java.util.stream.Collectors.joining(", "));
        log.warn("Validation failed: {}", errorMessage);
        return Result.fail(ErrorCode.PARAM_ERROR.getCode(), errorMessage);
    }

    /**
     * 处理路径参数/查询参数校验异常
     */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleConstraintViolationException(jakarta.validation.ConstraintViolationException e) {
        String errorMessage = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(java.util.stream.Collectors.joining(", "));
        log.warn("Constraint violation: {}", errorMessage);
        return Result.fail(ErrorCode.PARAM_ERROR.getCode(), errorMessage);
    }
```

- [ ] **步骤 2.2: 提交代码**
  `git add cloud-infra-common/src/main/java/com/cloudinfra/common/core/exception/GlobalExceptionHandler.java`
  `git commit -m "feat(common): handle validation exceptions in GlobalExceptionHandler"`

### 任务 3: 实施 DTO 校验 (示例应用)

- [ ] **步骤 3.1: 在 DTO 中添加注解**
  选取 `cloud-infra-api` 中的关键 DTO（如用户创建/更新请求），添加 `@NotBlank`, `@Email`, `@Size` 等注解。

- [ ] **步骤 3.2: 在 Controller 中启用校验**
  在对应的 Controller 方法参数前添加 `@Valid` 注解。

- [ ] **步骤 3.3: 提交代码**
  `git add cloud-infra-api/...`
  `git commit -m "feat(api): add validation constraints to DTOs"`

### 任务 4: 验证与测试

- [ ] **步骤 4.1: 编写校验测试用例**
  创建 `ValidationExceptionHandlerTest.java`，模拟发送不合法的 JSON 请求，验证返回的 `Result` 对象中 `code` 为 `1001 (PARAM_ERROR)` 且 `message` 包含具体的字段错误信息。

- [ ] **步骤 4.2: 运行并验证**
  执行 `mvn test -Dtest=ValidationExceptionHandlerTest`。

- [ ] **步骤 4.3: 提交代码**
  `git add cloud-infra-common/src/test/...`
  `git commit -m "test(common): add validation exception tests"`
