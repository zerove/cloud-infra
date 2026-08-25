# 网关 refresh scope 配置矛盾：AuthEvaluator @RefreshScope 在生产配置下必然失败

Status: needs-triage

## 现象

`CrossServiceFeignChainIntegrationTest`（进程隔离版 IT）排障时发现：
gateway 进程内每个请求都返回 500，日志根因为：

```
Security filter error: No Scope registered for scope name 'refresh'
```

## 根因

两个配置互相矛盾：

1. `cloud-infra-gateway/src/main/resources/application.yml` 显式声明
   `spring.cloud.refresh.enabled: false`（注释说明：AOT/Native Image 不支持 RefreshScope 动态刷新）。
2. `com.cloudinfra.gateway.domain.auth.AuthEvaluator`（认证判定核心）标注了 `@RefreshScope`。

refresh 关闭时 Spring Cloud 不会注册 `refresh` Scope，
`@RefreshScope` bean 在第一次实例化时即抛
`No Scope registered for scope name 'refresh'`。

## 影响面

- **本地/无 Nacos 环境**：认证完全不可用（所有请求 500）。
- **部署环境**：目前依赖 Nacos 配置中心导入时 spring-cloud-context 自动启用 refresh 才侥幸可用；
  一旦有人按注释字面意思在部署配置中也关掉 refresh，网关鉴权全挂。
- 该问题被"测试 profile 下从未真实走过 SecurityFilter"掩盖至今——本次跨服务 IT 是第一个
  真实命中该路径的自动化用例。

## 修复方向（三选一）

1. **去掉 `@RefreshScope`**（推荐）：白名单变更本就需重启生效的诉求存疑；
   若需动态性，改为每次请求从 Environment 读取或用独立的小配置类持有。
2. 开启 `spring.cloud.refresh.enabled=true` 并重新评估 AOT/Native 影响
   （与当初关闭它的动机冲突）。
3. `@RefreshScope` 换成条件化：仅当 refresh 可用时才代理。

IT 中当前以 `spring.cloud.refresh.enabled=true` 规避，修复后应移除该属性，
让 IT 反映生产默认配置。

## 复现

```
cd cloud-infra-gateway
mvn surefire:test -Dtest=CrossServiceFeignChainIntegrationTest -DfailIfNoTests=false -DskipITs=false
# 移除 IT 中的 refresh enabled 属性后：3 个用例全部 500
```
