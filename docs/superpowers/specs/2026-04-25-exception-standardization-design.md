# 全局异常处理标准化设计方案

## 1. 目标
增强全局异常处理能力，提供精细化的参数校验反馈与业务异常提示。

## 2. 核心规范
- **参数校验**: 捕获 `MethodArgumentNotValidException` (JSR-303) 和 `ConstraintViolationException`，提取首个校验错误信息返回。
- **业务异常**: `GlobalException` 必须支持 `ResultCode`，由全局处理器自动提取状态码与消息。
- **统一响应**: 异常时返回 JSON 结构：`{code: X, msg: '...', data: null}`。

## 3. 实施内容
1. 在 `GlobalExceptionHandler` 中增加针对 `MethodArgumentNotValidException` 的处理逻辑。
2. 确保 `GlobalException` 类能够接收并处理 `ResultCode`。
3. 移除项目中各 Controller 层手动 try-catch 后构造 Map 的逻辑。
