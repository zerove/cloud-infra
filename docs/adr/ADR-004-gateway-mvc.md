# ADR-004: 网关采用 Spring MVC（WebMvc）而非 WebFlux

**状态**: 已接受  
**日期**: 2026-04-22  
**提出者**: 架构组  

## 背景

Spring Cloud Gateway 默认基于 **WebFlux（Reactive）** 构建。但在 JDK 25 虚拟线程环境下，Reactive 编程模型的优势被削弱——虚拟线程已经能高效处理阻塞操作。

## 备选方案

- **WebFlux Gateway**: Spring 官方默认方案，非阻塞 I/O，但编程模型复杂，调试困难
- **Spring MVC Gateway**: 基于 Servlet 的阻塞模型，配合虚拟线程后可获得与 Reactive 相近的吞吐量

## 决策

采用 `spring-cloud-starter-gateway-server-webmvc`（Spring MVC 版本）构建网关，放弃 Reactive（WebFlux）。

### 实施细节

1. 使用 `spring-cloud-starter-gateway-server-webmvc` 替代 `spring-cloud-starter-gateway`
2. 网关过滤器使用标准的 `OncePerRequestFilter`（Servlet Filter），而非 WebFlux 的 `GatewayFilter`
3. 认证判定逻辑封装为纯领域对象 `AuthEvaluator`，不依赖任何 Servlet/Web API，具有高可测试性
4. 身份传播通过 `HttpServletRequestWrapper`（`IdentityRequestWrapper`）修改请求头

## 后果

**正面**:
- 编程模型简单，使用标准 `@RestController` 和 `Filter` 即可
- 虚拟线程消除了阻塞调用的性能损失
- 与框架中的 Spring Security、MyBatis 等同步库完美兼容
- `AuthEvaluator` 纯领域设计，无需 mock Servlet API

**负面**:
- 失去了 WebFlux 的背压（back-pressure）机制
- 虚拟线程的堆栈可能较深，对 OOM 的抵抗力略低于 Reactive 模型

## 关联

- 实施计划: `docs/superpowers/plans/2026-04-22-gateway-mvc-migration.md`
- 设计文档: `docs/superpowers/specs/2026-04-21-system-mvc-migration-design.md`
- 相关代码: `SecurityFilter`, `AuthEvaluator`, `IdentityRequestWrapper`
