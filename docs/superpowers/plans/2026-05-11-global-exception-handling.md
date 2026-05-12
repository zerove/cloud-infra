# 统一全局异常处理与标准错误码体系实现计划

> **面向智能体工作者**: 必须使用的子技能: 建议使用 `superpowers:subagent-driven-development` 或 `superpowers:executing-plans` 逐项执行此计划。步骤使用复选框 (`- [ ]`) 语法进行跟踪。

**目标**: 建立一套全项目统一的错误码体系和响应包装类，确保所有微服务在异常发生时返回格式一致的 JSON 响应。

**架构**: 
1. 在 `cloud-infra-common` 中定义 `ErrorCode` 枚举和 `BaseException` 基类。
2. 引入 `Result<T>` 统一响应包装类。
3. 在 `cloud-infra-common` 中实现 `GlobalExceptionHandler` 拦截所有异常并转化为 `Result` 对象。
4. 通过 `cloud-infra-starter` 的 `GlobalExceptionAutoConfiguration` 实现自动配置。

**技术栈**: Spring Boot 4, Jakarta Validation, Lombok.

---

## 文件结构映射

- **创建**: `cloud-infra-common/src/main/java/com/cloudinfra/common/core/domain/Result.java` - 统一响应体
- **创建**: `cloud-infra-common/src/main/java/com/cloudinfra/common/core/exception/ErrorCode.java` - 标准错误码枚举
- **创建**: `cloud-infra-common/src/main/java/com/cloudinfra/common/core/exception/BaseException.java` - 业务异常基类
- **修改**: `cloud-infra-common/src/main/java/com/cloudinfra/common/core/exception/GlobalExceptionHandler.java` - 异常拦截逻辑
- **创建**: `cloud-infra-common/src/test/java/com/cloudinfra/common/core/exception/GlobalExceptionHandlerTest.java` - 验证测试

---

## 详细执行步骤

### 任务 1: 定义统一响应体 `Result<T>`

- [ ] **步骤 1.1: 创建 `Result` 类**
  在 `com.cloudinfra.common.core.domain` 包下创建 `Result.java`。

```java
package com.cloudinfra.common.core.domain;

import lombok.Data;
import java.io.Serializable;

@Data
public class Result<T> implements Serializable {
    private boolean success;
    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setSuccess(true);
        result.setCode(200);
        result.setMessage("操作成功");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
```

- [ ] **步骤 1.2: 提交代码**
  `git add cloud-infra-common/src/main/java/com/cloudinfra/common/core/domain/Result.java`
  `git commit -m "feat(common): add unified Result response wrapper"`

### 任务 2: 建立错误码与业务异常体系

- [ ] **步骤 2.1: 创建 `ErrorCode` 枚举**
  在 `com.cloudinfra.common.core.exception` 包下创建 `ErrorCode.java`。

```java
package com.cloudinfra.common.core.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    // 系统级错误 (1000-1999)
    SYSTEM_ERROR(1000, "系统内部错误，请稍后重试"),
    PARAM_ERROR(1001, "请求参数错误"),
    UNAUTHORIZED(1002, "认证失败，请重新登录"),
    FORBIDDEN(1003, "权限不足，无法访问"),
    NOT_FOUND(1004, "请求资源不存在"),

    // 业务级错误 (2000+)
    USER_NOT_FOUND(2001, "用户不存在"),
    AUTH_TOKEN_INVALID(2002, "认证令牌无效或已过期");

    private final Integer code;
    private final String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
```

- [ ] **步骤 2.2: 创建 `BaseException` 异常类**
  在 `com.cloudinfra.common.core.exception` 包下创建 `BaseException.java`。

```java
package com.cloudinfra.common.core.exception;

import lombok.Getter;

@Getter
public class BaseException extends RuntimeException {
    private final ErrorCode errorCode;

    public BaseException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BaseException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode;
    }
}
```

- [ ] **步骤 2.3: 提交代码**
  `git add cloud-infra-common/src/main/java/com/cloudinfra/common/core/exception/ErrorCode.java cloud-infra-common/src/main/java/com/cloudinfra/common/core/exception/BaseException.java`
  `git commit -m "feat(common): implement standard ErrorCode and BaseException"`

### 任务 3: 实现全局异常处理器 `GlobalExceptionHandler`

- [ ] **步骤 3.1: 重构 `GlobalExceptionHandler`**
  修改 `cloud-infra-common/src/main/java/com/cloudinfra/common/core/exception/GlobalExceptionHandler.java`，使其返回 `Result` 对象。

```java
package com.cloudinfra.common.core.exception;

import com.cloudinfra.common.core.domain.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理自定义业务异常
     */
    @ExceptionHandler(BaseException.class)
    @ResponseStatus(HttpStatus.OK) // 业务异常通常返回 200，在 Result 中体现错误码
    public Result<?> handleBaseException(BaseException e) {
        log.error("Business exception: {}", e.getMessage());
        return Result.fail(e.getErrorCode().getCode(), e.getMessage());
    }

    /**
     * 处理参数校验异常 (未来集成 jakarta.validation 后在此扩展)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Parameter error: {}", e.getMessage());
        return Result.fail(ErrorCode.PARAM_ERROR.getCode(), e.getMessage());
    }

    /**
     * 处理所有未捕获的运行时异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleException(Exception e) {
        log.error("Unhandled exception occurred", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage());
    }
}
```

- [ ] **步骤 3.2: 提交代码**
  `git add cloud-infra-common/src/main/java/com/cloudinfra/common/core/exception/GlobalExceptionHandler.java`
  `git commit -m "refactor(common): update GlobalExceptionHandler to use Result and ErrorCode"`

### 任务 4: 验证与测试 (TDD)

- [ ] **步骤 4.1: 创建集成测试类**
  在 `cloud-infra-common/src/test/java/com/cloudinfra/common/core/exception/GlobalExceptionHandlerTest.java` 中编写测试。

```java
package com.cloudinfra.common.core.exception;

import com.cloudinfra.common.core.domain.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@WebMvcTest(GlobalExceptionHandler.class)
public class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void testBaseExceptionHandling() throws Exception {
        // 模拟一个触发 BaseException 的请求 (需配合一个简单的 TestController)
        mockMvc.perform(get("/test/throw-base-exception"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.success").value(false))
               .andExpect(jsonPath("$.code").value(2001)) // 假设抛出 USER_NOT_FOUND
               .andExpect(jsonPath("$.message").exists());
    }

    @Test
    public void testGenericExceptionHandling() throws Exception {
        mockMvc.perform(get("/test/throw-generic-exception"))
               .andExpect(status().isInternalServerError())
               .andExpect(jsonPath("$.success").value(false))
               .andExpect(jsonPath("$.code").value(1000))
               .andExpect(jsonPath("$.message").value("系统内部错误，请稍后重试"));
    }
}
```

- [ ] **步骤 4.2: 运行测试并验证**
  执行: `mvn test -Dtest=GlobalExceptionHandlerTest`
  预期结果: 所有测试用例 PASS。

- [ ] **步骤 4.3: 提交测试代码**
  `git add cloud-infra-common/src/test/java/com/cloudinfra/common/core/exception/GlobalExceptionHandlerTest.java`
  `git commit -m "test(common): add integration tests for GlobalExceptionHandler"`
