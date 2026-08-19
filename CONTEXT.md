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
    *   `framework-seata`: Distributed transaction management (optional, via ConditionalOnClass).
    *   `framework-security`: Unified security and authorization logic (HeaderAuthenticationFilter, PermissionService).
    *   `framework-tracing`: Distributed tracing for request observability (OTel, Zipkin).
*   **`cloud-infra-starter`**: Auto-configuration aggregation (tenant, platform capabilities).
*   **`cloud-infra-modules`**: Business-specific services built on top of the framework:
    *   `system-module`: Core system management (users, roles, permissions, config, dict).
    *   `file-service`: Object storage abstraction and file management.
    *   `notification-service`: Distributed message push and notification delivery.
    *   `search-service`: Elasticsearch-based high-performance search.

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
*   **Database/Cache**: PostgreSQL 16, Redis 7.2, MyBatis Plus 3.5.6
*   **Distributed Transactions**: Seata (optional, disabled by default for system-module)
*   **Container**: Podman, Kind, Helm

## 📖 How to Use This Repository
Developers should start by looking at the `docs/superpowers` directory for specific implementation plans and designs before modifying core framework components.

## ✅ Recent Optimizations (2026-08)
*   Security hardening: removed all hardcoded secrets, JWT weak key blacklist, management endpoint convergence.
*   Architecture: framework-security optional api, removed spring-cloud-stream from common.
*   Performance: KEYS→SCAN, N+1 fixes, pagination LIMIT, RabbitListener concurrency, Feign timeouts, ZGC+512m.
*   Quality: @Transactional rollbackFor, Feign out of transactions, idempotent consumers, DLQ config, StreamingResponseBody.
*   Deployment: compose restart/healthcheck, Helm PDB, prometheus port 9301, Seata disabled, dirty file cleanup.
*   Shared config: actuator-config.yaml with OTLP 4318, TRACE_SAMPLING_PROBABILITY, feign timeouts.