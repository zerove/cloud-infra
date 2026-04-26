# 用户上下文透传与鉴权完善设计文档

## 1. 目标
通过网关统一鉴权并注入用户上下文 (Header)，在业务模块实现全链路用户身份透传。

## 2. 架构设计
### 2.1 网关层 (cloud-infra-gateway)
- **GlobalAuthFilter**: 在校验 JWT 合法性及 Redis 有效性后，将 `userId` 及 `username` 封装至 Http Header (`X-User-Id`, `X-User-Name`)，透传至下游。

### 2.2 公共层 (cloud-infra-common)
- **UserContext**: 内部维护 `ThreadLocal<LoginUser>`，提供静态方法访问当前登录用户。
- **UserContextInterceptor**: 实现 `HandlerInterceptor`，从 Request Header 读取 `X-User-Id`/`X-User-Name` 并填充至 `UserContext`。

### 2.3 业务层 (system-module / etc)
- 直接使用 `UserContext.getUser()` 获取用户信息，无需重复鉴权。

## 3. 数据流
1. 用户 -> 网关 (校验 Token)
2. 网关 -> 业务模块 (Header 注入用户信息)
3. 业务模块 (拦截器) -> UserContext (填充)
4. 业务逻辑 (Controller/Service) -> 访问 UserContext

## 4. 安全性
- 网关强制覆盖 (Overwrite) Header 字段，防止外部伪造请求头。

## 5. 验证
- 编写集成测试，模拟 Header 请求，验证 UserContext 填充正确性。
