# MVC 架构迁移与虚拟线程适配设计规范

## 1. 概述
本项目将从 WebFlux (响应式) 架构迁移至 Spring MVC (同步/阻塞) 架构，并启用 Java 虚拟线程 (Virtual Threads) 以处理高并发请求。

## 2. 核心架构决策
- **框架迁移**: 模块依赖由 `spring-boot-starter-webflux` 切换至 `spring-boot-starter-web`。
- **并发模型**: 启用 Java 虚拟线程，配置 `spring.threads.virtual.enabled=true`。
- **IO 模式**: 移除所有响应式类型（Mono/Flux），统一采用同步阻塞式编程模型。

## 3. 模块适配策略
- **Framework层**: 
    - `framework-redis`: 移除 reactive 依赖，改用 `spring-boot-starter-data-redis` (Jedis/Lettuce 同步)。
    - `framework-security`: 移除基于 WebFlux 的 Filter，改用 Servlet Filter 或 HandlerInterceptor。
    - `framework-web`: 调整全局异常处理逻辑，统一使用 `@ControllerAdvice`。
- **Gateway**: 
    - 替换 `spring-cloud-starter-gateway` 为 `spring-cloud-starter-gateway-mvc`。
- **业务模块**:
    - `system-module` 等业务模块的 Controller 和 Service 改为标准同步写法。

## 4. 迁移路径
1. **基础设施适配**: 优先更新 `framework-*` 模块依赖。
2. **网关迁移**: 替换 `cloud-infra-gateway` 的网关核心库。
3. **业务对齐**: 按优先级分批迁移业务模块（以 `Auth` 和 `System` 为先）。
