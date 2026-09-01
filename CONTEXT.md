# Project Context: cloud-infra

## 🌟 Overview
`cloud-infra` is a comprehensive cloud infrastructure platform designed to provide a standardized, reusable foundation for microservices. It eliminates the need for each new service to reinvent core infrastructure concerns, ensuring consistency in monitoring, security, communication, and data access across the entire ecosystem.

## 🏗️ Architecture
The project follows a modular architecture to decouple core infrastructure capabilities from specific business logic.

### 1. Layered Structure
*   **`cloud-infra-api`**: Centralized API definitions and contracts (Feign clients, DTOs).
*   **`cloud-infra-auth`**: Specialized identity and access management service (JWT, login, captcha).
*   **`cloud-infra-gateway`**: The entry point for all external traffic, handling routing, authentication propagation, and cross-cutting concerns.
*   **`cloud-infra-common`**: The base layer providing centralized dependency management and shared utilities.
*   **`cloud-infra-framework`**: The core "engine" of the platform. It provides a set of standardized starter modules:
    *   `framework-web`: Standardized web capabilities, global exception handling, logging aspects.
    *   `framework-mybatis`: Standardized data access patterns, audit log, outbox.
    *   `framework-redis`: Standardized caching, multi-level cache, distributed locking.
    *   `framework-security`: Unified security and authorization logic (HeaderAuthenticationFilter, PermissionService).
    *   `framework-tracing`: Distributed tracing for request observability (OTel, Zipkin).
*   **`cloud-infra-starter`**: Auto-configuration aggregation (tenant, platform capabilities).
*   **`cloud-infra-modules`**: Business-specific services built on top of the framework:
    *   `system-module`: Core system management (users, roles, permissions, config, dict).
    *   `file-service`: Object storage abstraction and file management.
    *   `notification-service`: Distributed message push and notification delivery.
    *   `search-service`: Elasticsearch-based high-performance search.
*   **`cloud-infra-codegen`** (2026-08 新增): 代码生成框架, 消除 RuoYi 风格手工 CRUD 样板:
    *   `cloud-infra-codegen-core`: FreeMarker 模板 + 表元数据解析 (含 FlywayDdlParser, 无需 DB 连接).
    *   `cloud-infra-codegen-maven-plugin`: Maven 插件 (JDK 17 编译, 自包含精简模板).

### 2. Key Technical Decisions
*   **Configuration Management**: Uses **Nacos** for centralized configuration. Shared configurations (e.g., `actuator-config.yaml`) are used to ensure consistency across services.
*   **Observability**:
    *   **Actuator**: Every service exposes a management port (**9301**) with a standardized path (`/management`).
    *   **Metrics**: Integrated **Micrometer Prometheus** for standardized metrics collection.
    *   **Tracing**: **OpenTelemetry** via OTLP to collector (`otel-collector:4318`), configurable sampling rate (`TRACE_SAMPLING_PROBABILITY`).
*   **Communication**: Event-driven via **RabbitMQ** (Spring Cloud Stream removed from common). Services use `@RabbitListener` with concurrency tuning.
*   **Security**: Gateway validates JWT and propagates identity via headers (`X-User-Id`, `X-User-Name`). Downstream services use `@PreAuthorize` with `@ss.hasPermi`.
*   **Virtual Threads**: Enabled globally (`spring.threads.virtual.enabled=true`), ZGC for microsecond pause.
*   **Dependency Flow**: `common` → `framework` (optional `api`) → `modules/auth/gateway`.

## 🛠️ Tech Stack
*   **Language**: Java 25 (Virtual Threads)
*   **Framework**: Spring Boot 4.1.0, Spring Cloud 2025.1.2
*   **Config**: Nacos 3.x
*   **Observability**: Spring Boot Actuator, Micrometer, Prometheus, OpenTelemetry, Zipkin
*   **Messaging**: RabbitMQ (direct consumer)
*   **Database/Cache**: PostgreSQL 16, Redis 7.2, MyBatis Plus 3.5.17
*   **Container**: Podman, Kind, Helm

## 📖 How to Use This Repository
Developers should start by looking at the `docs/superpowers` directory for specific implementation plans and designs before modifying core framework components.

## ✅ Recent Optimizations (2026-08)
*   Security hardening: removed all hardcoded secrets, JWT weak key blacklist, management endpoint convergence.
*   Architecture: framework-security optional api, removed spring-cloud-stream from common.
*   Performance: KEYS→SCAN, N+1 fixes, pagination LIMIT, RabbitListener concurrency, Feign timeouts, ZGC+512m.
*   Quality: @Transactional rollbackFor, Feign out of transactions, idempotent consumers, DLQ config, StreamingResponseBody.
*   Deployment: compose restart/healthcheck, Helm PDB, prometheus port 9301, dirty file cleanup.
*   Shared config: actuator-config.yaml with OTLP 4318, TRACE_SAMPLING_PROBABILITY, feign timeouts.

## ✅ Recent Optimizations (2026-09) — v1.0.0 候选
*   **P0-3b 端到端 401 验证** (commit 370dcd1 + ae06566): JWT blacklist 在直连业务模块场景下实测返回 HTTP 401 (含 X-Blacklist-Jti 响应头).
*   **Caffeine ClassLoader 死锁修复** (commit ef0d69d): cloud-system 内存 768m→2g + JAVA_OPTS 加 `--enable-native-access=ALL-UNNAMED`, 解决 Spring Boot 4.1 + Caffeine 3.x + JDK 25 ClassLoader 死锁.
*   **cloud-infra-codegen 模块新增** (commit b9a2a84..1359496): 10+ FreeMarker 模板 + FlywayDdlParser (无需 DB 连接), 15 张表自动生成测试通过.
*   **M5-04b SQL 模板 PG 化** (commit 8b6b6de): MySQL 语法 → PostgreSQL 16 风格 (BIGSERIAL / COMMENT ON / IF NOT EXISTS).
*   **M5-04c 模板增强** (commit 0ef0005): VO 加 @Schema 注解, UpdateDTO @Size 256→255.
*   **M5-05 FlywayDdlParser 端到端** (commit 2ff8d50): 15 表自动解析 + 智能识别 @TableLogic + SMALLINT/VARCHAR(N)/UNIQUE/DEFAULT.
*   **M5-03 业务告警指标 AOP 化** (commit 8430811): @BusinessMetric 注解 + AOP 切面, Counter 按需注册, 11 告警规则中 2 条业务类 (`auth_login_attempts_total` + `file_upload_failures_total`) 真正落地.