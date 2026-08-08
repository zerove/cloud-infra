# Resilience4j 弹性能力指南

本文档描述 Cloud-Infra 中基于 Resilience4j 的服务调用弹性能力：**熔断 (Circuit Breaker)**、**重试 (Retry)**、**限流 (Rate Limiter)** 的落地方式、配置与验证方法。

## 1. 能力总览

| 能力 | 注解 | 适用场景 | 落地位置 |
|------|------|----------|----------|
| 熔断 | `@CircuitBreaker` | 下游持续故障时快速失败,避免级联 | Feign 读/写操作 |
| 重试 | `@Retry` | 瞬时故障(网络抖动)自动重试 | Feign 读操作 |
| 限流 | `@RateLimiter` | 高并发写操作防刷、保护下游 | Feign 写操作(send/upload) |

三个切面通过 `@EnableAspectJAutoProxy` 作用于 Spring 代理 Bean（Feign 客户端），实例名（`name` 属性）与 `deploy/config/system-module.yaml` 中的 `resilience4j.*.instances.*` 一一对应。

## 2. 架构决策:为什么写在 Feign 客户端上

弹性注解直接标注在 `cloud-infra-api` 的 Feign 接口方法上,而非业务 Service:

- **调用方视角**: 消费方感知下游异常,在源头拦截,避免把故障请求送入调用链
- **契约即配置**: 弹性策略与 API 契约同处一处,新增下游服务时不易遗漏
- **多消费方复用**: 所有依赖 `NotificationApi`/`FileApi`/`SearchApi` 的服务自动获得相同弹性语义

## 3. 落地细节

### 3.1 切面激活(关键修复)

`Resilience4jAutoConfiguration`(位于 `cloud-infra-framework/framework-web`)负责导入官方自动配置:

```java
@Import({
    CircuitBreakerAutoConfiguration.class,
    RetryAutoConfiguration.class,
    RateLimiterAutoConfiguration.class,
    BulkheadAutoConfiguration.class
})
```

> ⚠️ 历史上只导入了 `CircuitBreakerAutoConfiguration`,导致 `@Retry`/`@RateLimiter`/`@Bulkhead` 注解**静默失效**。已修复并新增 `Resilience4jAspectTest` 验证。

### 3.2 注解分布

| Feign 客户端 | 方法 | 熔断 | 重试 | 限流 |
|--------------|------|:---:|:---:|:---:|
| `NotificationApi` | `send` | ✅ | ✅ | ✅ |
| `NotificationApi` | `getHistory` | ✅ | ✅ | — |
| `NotificationApi` | `markAsRead` | ✅ | — | — |
| `FileApi` | `upload` | ✅ | ✅ | ✅ |
| `FileApi` | `getFileInfo` | ✅ | ✅ | — |
| `FileApi` | `searchFiles` | ✅ | — | — |
| `SearchApi` | `search` | ✅ | ✅ | — |

设计原则:

- **写操作** 加限流防刷(`send`/`upload`);重试仅对**读/幂等写**开放,避免重复提交
- **读操作** 全部走熔断+重试组合,重试配合熔断可在瞬时故障时自动恢复

### 3.3 配置(`deploy/config/system-module.yaml`)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      notificationService: { failure-rate-threshold: 50, sliding-window-size: 100, minimum-number-of-calls: 10, wait-duration-in-open-state: 30s }
      fileService:          { ... 同上 ... }
      searchService:        { ... 同上 ... }
  ratelimiter:
    instances:
      notificationService:  { limit-refresh-period: 1s, limit-for-period: 100, timeout-duration: 500ms }
      fileService:          { limit-refresh-period: 1s, limit-for-period: 100, timeout-duration: 500ms }
  retry:
    max-attempts: 3
    wait-duration-ms: 500
    exponential-backoff: true
    exponential-multiplier: 2.0
    retry-exceptions: [IOException, TimeoutException]
    ignore-exceptions: [IllegalArgumentException, IllegalStateException]
    instances:
      notificationService:  { max-attempts: 3, wait-duration: 500ms }
      fileService:          { max-attempts: 3, wait-duration: 500ms }
      searchService:        { max-attempts: 3, wait-duration: 500ms }
```

要点:

- `retry-exceptions` 只对瞬时异常重试(IO/超时),`ignore-exceptions` 排除业务参数错误,避免无效重试
- `NotificationApi.send`/`FileApi.upload` 的 `@RateLimiter` 使用与熔断/重试相同的实例名,共享 100 req/s 配额
- 全局默认值(如 `limit-for-period: 100`)作为兜底,实例配置按服务覆盖

## 4. 行为说明

### 4.1 熔断状态机

| 状态 | 触发条件 | 行为 |
|------|----------|------|
| CLOSED | 正常 | 全部放行 |
| OPEN | 滑动窗口 100 次内失败率 ≥ 50%(至少 10 次调用) | 直接拒绝,抛 `CallNotPermittedException` |
| HALF_OPEN | 打开 30s 后 | 放行 10 个探测请求,成功则关闭,失败则重回打开 |

### 4.2 重试与熔断协同

`@Retry` 与 `@CircuitBreaker` 同时标注时,执行顺序为: **熔断 → 重试 → 业务**。即熔断先检查(状态打开则拒绝),重试在熔断放行后对瞬时异常进行指数退避重试(3 次,500ms 起步,倍增)。

### 4.3 限流

写操作防刷: 1 秒窗口 100 个许可,超限后等待 500ms 仍不可得则抛 `RequestNotPermitted`。

## 5. 配置修改指南

1. 修改 `deploy/config/system-module.yaml` 中对应 `instances` 配置
2. 运行 `./deploy/up.sh` 同步至 Nacos
3. 相关服务滚动重启生效

## 6. 验证

单元级:

```bash
mvn test -pl cloud-infra-framework/framework-web -Dtest=Resilience4jAspectTest
```

`Resilience4jAspectTest` 覆盖 4 个用例:

- `@Retry` 重试至成功(3 次调用)
- `@Retry` 耗尽退出(3 次失败)
- `@CircuitBreaker` 熔断打开后抛 `CallNotPermittedException`
- `@RateLimiter` 周期内超限抛 `RequestNotPermitted`

> 该测试显式 `@Import(AopAutoConfiguration)` 验证 Spring AOP 代理链,避免依赖完整自动装配(无需 Redis/数据源)。

## 7. 与网关限流的分工

- **网关层**(Sentinel/Gateway 过滤器): 面向**外部流量**的入口限流,保护整个集群
- **Resilience4j**(Feign 层): 面向**服务间调用**的出口弹性,快速失败 + 重试 + 防重放

两层互补,外部打爆由网关拦截,下游抖动由服务间弹性吸收。