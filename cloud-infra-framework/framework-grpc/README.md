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

证书支持文件路径或 `classpath:` 路径。测试可配置唯一的
`cloudinfra.grpc.in-process-server-name`，此模式不开放网络端口。
网络模式将调用任务交给每任务虚拟线程执行器。

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

本模块只提供服务端传输与生命周期管理，不代表业务鉴权、租户隔离或客户端身份
传播已完成。独立网络端口不会自动经过现有网关及其过滤器，业务服务在补齐这些
边界前不得向不受信任的网络开放。传输加密不能替代调用者鉴权。
网关协议转换、流式调用及性能基准仍属后续工作。
