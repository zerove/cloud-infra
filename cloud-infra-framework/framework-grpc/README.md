# 框架 gRPC 集成

本模块使用原生 gRPC 和 Spring 生命周期管理，无 LogNet 启动器依赖。

## 接入

业务模块引入 `com.cloudinfra:framework-grpc`，配置类添加 `@EnableGrpcServer`。
将生成服务基类的实现注册为 Spring 组件，即可自动发现所有 `BindableService`。
不需要 `@GRpcService`。

```java
@Service
public class MyServiceImpl extends MyServiceGrpc.MyServiceImplBase {
    // 调用业务服务层实现方法。
}
```

```yaml
cloudinfra:
  grpc:
    enabled: true
    port: 9090
    max-inbound-message-size: 4194304
    ssl:
      enabled: true
      cert-chain-file: ${GRPC_CERT_CHAIN_FILE}
      private-key-file: ${GRPC_PRIVATE_KEY_FILE}
```

证书支持文件路径、`file:` 或 `classpath:` 路径。测试可配置唯一的
`cloudinfra.grpc.in-process-server-name`，此模式不开放网络端口。
网络模式将调用任务交给每任务虚拟线程执行器。

## 调用安全边界

默认关闭。开启后拦截器在业务代码执行前完成三道校验，并为业务层补齐身份与租户上下文。

```yaml
cloudinfra:
  grpc:
    security:
      enabled: true
      require-internal-sign: true   # 校验 X-Internal-Call / -Timestamp / -Nonce HMAC
      require-tenant: true          # 要求 X-Tenant-Id
      require-user: true            # 要求 X-User-Id
```

| 配置 | 作用 | 缺省 |
| --- | --- | --- |
| `enabled` | 是否注册安全边界拦截器 | `false` |
| `require-internal-sign` | 与 HTTP 侧 `HeaderAuthenticationFilter` 同一套密钥与 5 分钟时间窗 | `true` |
| `require-tenant` | 绑定 `TenantContext`，使 MyBatis 租户拦截器生效 | `true` |
| `require-user` | 允许纯服务间调用时可置 `false` | `true` |

任一校验失败即以 `UNAUTHENTICATED` 关闭调用，错误描述统一为 `Unauthenticated`，
不区分“缺签名”与“签名错误”，避免攻击者据此枚举。开启且密钥缺失时服务拒绝启动。

业务层读取身份有两条路径：

```java
// 1. gRPC 原生上下文（无 spring-security 依赖）
PlatformCaller caller = GrpcCallerContext.current();

// 2. 既有 Spring Security 链路（classpath 上有 framework-security 时自动装配）
Long userId = SecurityUtils.getUserId();
String tenant = TenantContext.getTenantIdOrDefault();
```

线程上下文仅在业务代码执行期间存在，每个监听器回调后立即回收，不跨调用残留。

## 生命周期

初始化阶段只检查服务列表。服务器在 `SmartLifecycle.start()` 阶段启动。
停止时先等待在途请求，再强制关闭超时请求并释放执行器。重启创建新的执行器。
启动失败会清理已创建的服务器及执行器，证书输入流使用后关闭。

## 验证

单元测试与集成测试按根构建配置分离，`mvn test` 不执行 `*IntegrationTest`。
执行本模块生命周期集成测试：

```sh
mvn -o -T 1 -pl cloud-infra-framework/framework-grpc -am verify \
  -DskipITs=false -Dit.test=GrpcServerLifecycleIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false -Dcheckstyle.skip -Dspotbugs.skip
```

结果位于 `target/failsafe-reports`。测试直接调用容器管理的服务器，覆盖进程内
调用、真实网络调用、服务发现、关闭、网络重启、初始化时序和禁用配置。

## 当前边界

本模块提供服务端传输、生命周期管理与**调用认证**（身份 + 租户上下文）。
尚未完成：方法级权限映射（`hasPermi` 在 gRPC 通道内恒为 false，写操作不可放开）、
数据范围过滤的业务侧接入、第二业务模块客户端、HTTP→gRPC 协议转换、
流式调用及性能基准。

独立网络端口不会自动经过现有网关及其过滤器，`verify-internal-sign` 关闭时任何
持有网络可达性的一方都可直接调用。传输加密与内部签名不能替代方法级授权。
