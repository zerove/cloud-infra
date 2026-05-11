# Cloud Infrastructure (Cloud-Infra)

A modern, high-performance cloud infrastructure platform built with Spring Boot 4, JDK 25, and Virtual Threads.

## Project Overview

This project serves as a foundational infrastructure for microservices, focusing on high concurrency, distributed architecture, and developer productivity.

- **Primary Technologies:** Spring Boot 4.0.5, JDK 25, Spring Cloud 2025.
- **Microservices Framework:** Spring Cloud Alibaba (Nacos for Config/Discovery, Seata for Distributed Transactions).
- **Persistence:** MyBatis Plus 3.5.6, MySQL 8.4 / PostgreSQL 16.
- **Cache & Concurrency:** Redisson 3.30.0.
- **Observability:** Micrometer Tracing, Prometheus, Actuator.
- **Security:** RBAC, JWT (jjwt 0.12.5), Spring Security.
- **Tools:** Lombok, Hutool, Testcontainers.

## Architecture

The project is structured as a multi-module Maven project located in the `cloud-infra/` directory.

- **`cloud-infra-gateway`**: Spring Cloud Gateway (MVC based) acting as the entry point (Port: 9000).
- **`cloud-infra-auth`**: Centralized Authentication and Authorization service (Port: 9200).
- **`cloud-infra-framework`**: Core framework modules (Web, MyBatis, Redis, Security, Tracing).
- **`cloud-infra-common`**: Shared utilities, constants, and base classes.
- **`cloud-infra-modules`**:
  - `system-module`: Main administration and RBAC service (Port: 9300).
  - `file-service`: Object storage and file management.
  - `notification-service`: Messaging and notification delivery.
  - `search-service`: Search functionality based on Elasticsearch.
- **`cloud-infra-starter`**: Project entry points and bootstrap configurations.
- **`cloud-infra-api`**: Feign clients and DTOs for inter-service communication.
- **`cloud-infra-test-support`**: Infrastructure for integration testing with Testcontainers.

## Building and Running

### Prerequisites
- JDK 25
- Maven 3.9+
- Podman / Docker (for orchestration)

### Build
From the `cloud-infra/` root directory:
```bash
mvn clean install -DskipTests
```

### Run (Development Environment)
The `cloud-infra/deploy` directory contains scripts for local orchestration using `podman-compose`.

1. **Start Infrastructure & Services:**
   ```bash
   cd cloud-infra/deploy
   ./up.sh
   ```
   This script performs the following:
   - Starts Middleware: Nacos (8848), Redis (6379), PostgreSQL (5432).
   - Syncs configurations to Nacos.
   - Starts Microservices: Gateway, Auth, System.
   - Sets up Prometheus for monitoring.

2. **Access Points:**
   - **Nacos Console:** http://localhost:8848/nacos
   - **API Gateway:** http://localhost:9000
   - **System Module:** http://localhost:9300

## Development Conventions

- **JDK 25 Features:** Leverages Virtual Threads for high-throughput I/O operations.
- **Lombok:** Use `@Data`, `@Slf4j`, and constructor injection via `@RequiredArgsConstructor`.
- **MyBatis Plus:** Follow the standard Mapper/Service/ServiceImpl pattern. Use `LambdaQueryWrapper` for type-safe queries.
- **Security:** RBAC is enforced at the gateway and service levels. Use `@PreAuthorize` for method-level security.
- **Tracing:** All requests are traced via Micrometer. Ensure logs include `traceId` and `spanId`.
- **Testing:** New features should include integration tests using the `BaseIntegrationTest` (which leverages Testcontainers).
- **Configuration:** Business configurations are managed in Nacos. Local `application.yml` should only contain bootstrap/environment-specific settings.
