# 登录认证服务设计规格说明书

**目标**：在 `cloud-infra-auth` 模块实现基于本地数据库的登录认证接口，并集成 `framework-security` 进行 Token 签发与用户信息返回。

## 1. 架构方案
- **模块职责**：`cloud-infra-auth` 负责接收登录请求，调用 `system-module` 用户查询服务，进行 BCrypt 密码比对。
- **通信机制**：采用 OpenFeign 实现 `auth` 与 `system` 模块的远程调用。
- **鉴权集成**：使用 `framework-security` 中定义的 `JwtUtils` 生成含 `jti` 的 Token。

## 2. 接口定义 (`POST /auth/login`)
### 2.1 请求参数
- `username` (String)
- `password` (String)

### 2.2 响应结构
```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "accessToken": "eyJhbGci...",
    "userInfo": {
      "userId": 1001,
      "username": "admin",
      "roles": ["ROLE_ADMIN"]
    }
  }
}
```

## 3. 实现细节
- **BCrypt 校验**：使用 `org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder`。
- **用户信息获取**：通过 Feign 远程调用 `system-module` 获取用户信息。
- **登录记录**：建议在调用成功后异步记录最后登录时间（扩展功能）。

## 4. 安全预留
- **OAuth2 兼容**：通过 Service 层接口隔离 `LocalAuthService` 和 `OAuth2AuthService`，后续接入第三方认证时只需扩展新 Service。

---

**Spec 已编写完成，请 review 以上设计方案是否符合预期。如无异议，我将使用 `writing-plans` skill 创建具体的实施计划。**
