# Ubiquitous Language: cloud-infra

This document defines the canonical terminology used across the `cloud-infra` project to ensure consistent communication between developers, architects, and AI agents.

## 🏗️ Infrastructure Components

| Term | Definition | Scope |
| :--- | :--- | :--- |
| **Framework (`cloud-infra-framework`)** | The core library providing a set of standardized "starter" modules (Web, Redis, MyBatis, Security, etc.) that every business service must use. | Global |
| **Common (`cloud-infra-common`)** | The base layer containing shared utilities and centralized dependency management. | Global |
| **Gateway (`cloud-infra-gateway`)** | The unified entry point for all external requests, handling routing, rate limiting, JWT validation, and identity propagation (`X-User-Id`, `X-User-Name`). | Perimeter |
| **Auth Service (`cloud-infra-auth`)** | The centralized identity provider responsible for authentication, token issuance, login locking, and captcha validation. | Core Service |
| **Business Module (`cloud-infra-modules`)** | A specific business service implementation built upon the framework (e.g., `system-module`, `file-service`, `notification-service`, `search-service`). | Business Layer |
| **Starter (`cloud-infra-starter`)** | Auto-configuration aggregator for platform capabilities (tenant context, platform properties). | Cross-cutting |

## ⚙️ Technical Concepts

| Term | Definition | Context |
| :--- | :--- | :--- |
| **Actuator Engineering** | The practice of standardizing Spring Boot Actuator endpoints across all services (management port 9301, base-path `/management`, exposed endpoints: health,info,prometheus,metrics,env,beans,conditions,refresh). | Observability |
| **StreamBridge** | **Deprecated** - Previously used Spring Cloud Stream component for async message dispatch. Replaced by direct `@RabbitListener` consumers. | Communication (Legacy) |
| **Binding** | A named channel used by Spring Cloud Stream to connect an application to an external messaging system. **Deprecated**. | Messaging (Legacy) |
| **Shared Config** | Configuration files stored in Nacos (e.g., `actuator-config.yaml`, `feign timeouts`, `otlp endpoint`) that are imported by multiple services to ensure consistency. | Configuration |
| **Distributed Transaction** | A transaction that spans multiple services, managed by the `framework-seata` module. Disabled by default for system-module. | Data Consistency |
| **Virtual Threads** | JDK 25 lightweight threads enabled globally (`spring.threads.virtual.enabled=true`), paired with G1GC (heap 256m) — note: ZGC was never actually in the runtime path. | Concurrency |
| **Header Authentication Filter** | Spring Security filter that reads `X-User-Id`/`X-User-Name` headers (injected by Gateway) and populates SecurityContext with permissions. | Security |
| **Internal Call Signature** | HMAC-signed headers (`X-Internal-Call-Sign`, `X-Internal-Call-Timestamp`, `X-Internal-Call-Nonce`) used to authorize internal Feign endpoints (e.g., `/system/user/**`). | Security |

## 🛠️ Domain-Specific Terms

| Term | Definition | Related Component |
| :--- | :--- | :--- |
| **TxMessage** | A transaction-related message that may require reliable delivery and retry logic. | `system-module` |
| **TxMessageRetryTask** | A scheduled task that polls for pending `TxMessage` and attempts to redeliver them (via direct RabbitMQ publish). | `system-module` |
| **AuthToken** | A secure JWT token issued by the Auth service to authenticate subsequent requests. | `cloud-infra-auth` |
| **Management Port** | The dedicated port (default: 9301) used exclusively for health checks, metrics, and administrative endpoints. | Observability |
| **Idempotent Consumer** | Message consumer that uses Redis SETNX (TTL 24h) to deduplicate processing based on `messageId` or payload hash. | `notification-service`, `search-service` |
| **DLQ (Dead Letter Queue)** | RabbitMQ dead-letter exchange/queue for failed messages after max retries, configured via `auto-bind-dlq: true`, `dlq-name`, `republish-to-dlq: true`. | `notification-service` |
| **File Magic Bytes** | File signature validation (first N bytes) to verify actual file type matches extension, preventing MIME spoofing. | `file-service` |

---

*Last Updated: 2026-08-18*