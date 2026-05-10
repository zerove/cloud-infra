# API 响应标准化设计方案

## 1. 目标
实现项目内所有 Controller 接口返回格式的完全统一，强制使用 `Result<T>` 封装。

## 2. 核心规范
- **响应体**: 所有 Controller 方法返回值必须为 `Result<T>`。
- **业务枚举**: 必须扩展 `ResultCode`，禁止使用硬编码的错误码。
- **异常捕获**: 所有业务异常必须通过 `GlobalException` 抛出，由 `GlobalExceptionHandler` 统一处理，禁止在 Controller 层手动处理异常返回 `Map`。

## 3. 实施路径
1. **AuthController 重构**: 将当前返回 `Map<String, Object>` 的方法重构为 `Result<Map<String, Object>>`。
2. **ResultCode 扩展**: 在 `ResultCode` 枚举中增加常用业务错误码（如 AUTH_EXPIRED, USER_NOT_FOUND）。
3. **全局清理**: 对各模块的 Controller 进行扫描，修复非规范的返回类型。

## 4. 验证
- 通过集成测试，验证登录/刷新 Token 接口返回的 JSON 结构。
- 确认 Swagger/Knife4j 文档生成正常。
