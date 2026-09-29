# System gRPC 实验性适配层

## 安全边界

**鉴权尚未完整，仍禁止在生产环境开启这些业务端点。**

- `cloudinfra.system.grpc.enabled` 默认缺失即禁用。显式 `false` 同样不会注册用户、部门适配组件，也不会导入本模块的服务器配置。
- `SystemApplication` 不再无条件添加 `@EnableGrpcServer`。
- 仅隔离开发测试环境可设置 `cloudinfra.system.grpc.enabled=true`，框架服务器还须满足自身 `cloudinfra.grpc.enabled` 条件。网络、端口、证书和生命周期由 `framework-grpc` 管理。

### 已完成：调用认证

开启 `cloudinfra.grpc.security.enabled=true` 后，`framework-grpc` 的拦截器在业务代码执行前完成：

| 边界 | 行为 | 失败结果 |
| --- | --- | --- |
| 内部调用签名 | 校验 `X-Internal-Call` / `-Timestamp` / `-Nonce` HMAC，与 HTTP 侧同一密钥和 5 分钟时间窗 | `UNAUTHENTICATED` |
| 调用方身份 | 提取 `X-User-Id` / `X-User-Name` | `UNAUTHENTICATED` |
| 租户上下文 | 提取 `X-Tenant-Id` 并绑定 `TenantContext` | `UNAUTHENTICATED` |

错误描述统一为 `Unauthenticated`，不区分失败原因。开启且签名密钥缺失时服务拒绝启动。

业务层通过 `GrpcCallerContext.current()` 读取调用方身份（可能为 `null`），
租户复用平台 `TenantContext`。**gRPC 通道不写入 Spring Security 上下文**：
独立网络端口不经过网关过滤链，用 HTTP 侧机制反推 gRPC 身份会掩盖真实来源。
线程上下文仅在业务代码执行期间存在，调用结束后立即回收。

### 仍未完成：方法级授权

- **“能调用”不等于“已获授权”。** `GrpcCaller` 未携带权限集合，
  gRPC 通道内无法做方法级鉴权，因此 **写操作（create / update / delete）禁止放开**。
- 数据范围过滤（`@DataScope`）依赖 `SecurityUtils.getDeptId()`，gRPC 入口未提供 `deptId`。
- `ResetPassword` 暂不实现，固定返回 `UNIMPLEMENTED`，不调用业务密码修改方法。
- 必须补齐方法级权限映射、数据范围过滤与相应安全测试，才能评估生产开放。

## 协议与行为

- 两类列表请求的页码为 `0` 时使用 `1`，页大小为 `0` 时使用 `20`。页码限制 `1..100000`，页大小限制 `1..100`，负数及超限返回 `INVALID_ARGUMENT`，不进入业务查询。
- 用户列表未传部门筛选时不添加部门条件，不再误传 `0`。状态仅支持字符串 `0`、`1`。
- 标识必须为正数，部门根父节点允许 `0`。批量删除用户限制 `1..100` 个输入标识，并去重。
- 创建请求通过现有 DTO 的 Jakarta 校验。新的 `createUserReturningId`、`createDeptReturningId` 事务方法返回实际插入实体的主键，适配层按主键读取，不按名称猜测或全表反查。旧网页接口创建方法保持原契约。
- 新用户创建方法使用请求密码加密，不沿用旧管理端创建方法的初始化密码覆盖行为。没有执行任何真实密码重置。
- 更新请求的可选标量字段使用 `optional`，未提供表示不修改，显式空字符串表示清空字符串。角色和岗位暂只支持一个值，多个值明确拒绝，空列表表示不修改，不支持清空关联。
- 用户实体尚无性别映射，因此拒绝提供性别的更新请求及非空性别创建请求。响应中的性别、部门名称、角色名称和岗位名称等未装配字段保持协议默认值，不额外查询。
- 部门树 `root_id=0` 返回完整树，大于 `0` 返回指定节点及后代，未找到返回 `NOT_FOUND`。树构建仍依赖既有业务服务，不新增无限制的名称反查。
- 可空数据库字段映射为空字符串、数值 `0`，缺少时间映射为 `0`。现有本地时间按服务 JVM 时区转换为毫秒时间戳，部署应统一时区。
- 参数问题返回固定 `INVALID_ARGUMENT`，资源不存在返回 `NOT_FOUND`，其余业务拒绝返回 `FAILED_PRECONDITION`，非预期运行时异常返回固定 `INTERNAL`。客户端不接收底层异常消息或原因。

## 本地验证

无需启动数据库、消息队列或外部服务器：

```bash
mvn -pl cloud-infra-modules/system-module -am compile -DskipTests -q
mvn -pl cloud-infra-modules/system-module -am test \
  -Dtest=SystemGrpcAdapterTest,SystemGrpcDisabledTest,SystemGrpcNetworkTest,SystemGrpcSecurityBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DskipITs -Djacoco.skip=true
```

测试覆盖组件默认/显式关闭和显式启用、分页及筛选边界、错误脱敏、创建生成主键、空值序列化、可选更新、未找到响应、删除输入、子树选择及密码重置未实现。

`SystemGrpcNetworkTest` 与 `SystemGrpcSecurityBoundaryTest` 走真实 TCP + TLS：
前者验证传输层往返、错误映射与容器关闭后服务器停止；后者验证安全边界
（无签名 / 伪造签名 / 缺租户被拒，合法签名放行，身份与租户在业务实现体内可见，
调用结束后上下文不残留）。两者复用部署证书脚本，随机密码，不做任何密码弱化。

这些测试使用模拟数据访问组件，不等同于数据库集成或生产鉴权验证。协议代码由 Maven 插件生成并参与真实模块编译。
