# Project Context: cloud-infra

## 🌟 Overview
`cloud-infra` is a comprehensive cloud infrastructure platform designed to provide a standardized, reusable foundation for microservices. It eliminates the need for each new service to reinvent core infrastructure concerns, ensuring consistency in monitoring, security, communication, and data access across the entire ecosystem.

## 🏗️ Architecture
The project follows a modular architecture to decouple core infrastructure capabilities from specific business logic.

### 1. Layered Structure
*   **`cloud-infra-api`**: Centralized API definitions and contracts.
*   **`cloud-infra-auth`**: Specialized identity and access management service.
*   **`cloud-infra-gateway`**: The entry point for all external traffic, handling routing and cross-cutting concerns.
*   **`cloud-infra-common`**: The base layer providing centralized dependency management and shared utilities.
*   **`cloud-infra-framework`**: The core "engine" of the platform. It provides a set of standardized starter modules:
    *   `framework-web`: Standardized web capabilities and integrated monitoring.
    *   `framework-mybatis`: Standardized data access patterns.
    *   `framework-redis`: Standardized caching and distributed locking.
    *   `framework-seata`: Distributed transaction management.
    *   `framework-security`: Unified security and authorization logic.
    *   `framework-tracing`: Distributed tracing for request observability.
*   **`cloud-infra-modules`**: Business-specific services built on top of the framework (e.g., `system-module` for core system tasks).

### 2. Key Technical Decisions
*   **Configuration Management**: Uses **Nacos** for centralized configuration. Shared configurations (e.g., `actuator-config.yaml`) are used to ensure consistency across services.
*   **Observability**:
    *   **Actuator**: Every service exposes a management port (**9301**) with a standardized path (`/management`).
    *   **Metrics**: Integrated **Micrometer Prometheus** for standardized metrics collection.
*   **Communication**: Employs an event-driven architecture using **Spring Cloud Stream** (`StreamBridge`) for asynchronous, decoupled message processing (e.g., `TxMessageRetryTask`).
*   **Dependency Flow**: `common` $\rightarrow$ `framework` $\rightarrow$ `modules/auth/gateway`.

## 🛠️ Tech Stack
*   **Language**: Java
*   **Framework**: Spring Boot, Spring Cloud
*   **Config**: Nacos
*   **Observability**: Spring Boot Actuator, Micrometer, Prometheus
*   **Messaging**: Spring Cloud Stream
*   **Database/Cache**: MyBatis, Redis, Seata (Distributed Transactions)

## 📖 How to Use This Repository
Developers should start by looking at the `docs/superpowers` directory for specific implementation plans and designs before modifying core framework components.
