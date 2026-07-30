# Cloud-Infra Developer Guide

This guide provides the standards and patterns for implementing new services and modules within the `cloud-infra` ecosystem.

## 1. Architecture Overview
`cloud-infra` is a distributed system built on Spring Boot 4 and JDK 25, utilizing a "Framework-Core" approach. Common logic is encapsulated in `cloud-infra-framework` to ensure consistency across all services.

## 2. Creating a New Service
To add a new microservice:
1. Create a new directory under `cloud-infra-modules/your-service`.
2. Add a `pom.xml` that inherits from `cloud-infra-modules`.
3. Define the `Application` class with `@SpringBootApplication`.
4. Configure `application.yml` for Nacos discovery and config.

## 3. Framework Integration
Every service should leverage the provided framework modules to avoid boilerplate:

| Module | Purpose | Integration |
| :--- | :--- | :--- |
| `framework-web` | Web/REST standards, Metrics, Monitoring | Add as dependency; automatically provides common metrics tags. |
| `framework-mybatis` | Database access (MyBatis Plus) | Use `BaseMapper` and `IService` for standard CRUD. |
| `framework-security` | AuthN/AuthZ, JWT integration | Configure `SecurityConfig` extending framework defaults. |
| `framework-tracing` | Distributed tracing (Brave/Zipkin) | Automatic trace propagation across service boundaries. |

## 4. Configuration Standards
### 4.1 Nacos Strategy
Use Nacos for environment-specific configurations. Local `application.yml` should only contain:
- `spring.application.name`
- Nacos server address and config imports.

### 4.2 Actuator Engineering (Management Port)
To ensure monitoring tools can reach all services consistently:
- **Management Port**: `9301`
- **Base Path**: `/management`
- **Configuration**:
  ```yaml
  management:
    server:
      port: 9301
    endpoints:
      web:
        base-path: /management
        exposure:
          include: "*"
  ```

## 5. Reliability & Messaging
### 5.1 Event-Driven Architecture
We use Spring Cloud Stream for asynchronous communication.
- **Producer**: Use `StreamBridge` for flexible dispatch.
- **Consumer**: Define `Consumer<T>` beans.

### 5.2 Dead Letter Queue (DLQ) Pattern
All critical streams MUST implement the DLQ pattern to prevent message loss:
1. **Auto-bind DLQ**: Enable `auto-bind-dlq: true` in `application.yml`.
2. **DLQ Consumer**: Implement a dedicated consumer for the `.dlq` destination to handle permanent failures and trigger alerts.

## 6. Observability
### 6.1 Metrics
Metrics are automatically collected via Micrometer. Every service in the project is tagged with `project: cloud-infra` via `framework-web` to allow global aggregation in Grafana.

### 6.2 Tracing
Distributed tracing is enabled by default. Ensure all cross-service calls use `OpenFeign` or `RestTemplate` provided by the framework to maintain trace context.

## 7. Deployment & Performance
### 7.1 Native Images
The project supports GraalVM Native Images for fast startup and low memory footprint. 
- Ensure all dependencies are compatible with native compilation.
- Use the `native-maven-plugin` for build.

### 7.2 Dockerization
Use the provided multi-stage `Dockerfile` templates to build optimized images.
