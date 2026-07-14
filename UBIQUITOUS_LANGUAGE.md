# Ubiquitous Language: cloud-infra

This document defines the canonical terminology used across the `cloud-infra` project to ensure consistent communication between developers, architects, and AI agents.

## 🏗️ Infrastructure Components

| Term | Definition | Scope |
| :--- | :--- | :--- |
| **Framework (`cloud-infra-framework`)** | The core library providing a set of standardized "starter" modules (Web, Redis, MyBatis, etc.) that every business service must use. | Global |
| **Common (`cloud-infra-common`)** | The base layer containing shared utilities and centralized dependency management. | Global |
| **Gateway (`cloud-infra-gateway`)** | The unified entry point for all external requests, handling routing, rate limiting, and security. | Perimeter |
| **Auth Service (`cloud-infra-auth`)** | The centralized identity provider responsible for authentication, token issuance, and permission management. | Core Service |
| **Business Module (`cloud-infra-modules`)** | A specific business service implementation built upon the framework (e.g., `system-module`). | Business Layer |

## ⚙️ Technical Concepts

| Term | Definition | Context |
| :--- | :--- | :--- |
| **Actuator Engineering** | The practice of standardizing Spring Boot Actuator endpoints across all services (e.g., using port 9301 and `/management` path). | Observability |
| **StreamBridge** | The Spring Cloud Stream component used to dispatch messages to output bindings asynchronously. | Communication |
| **Binding** | A named channel used by Spring Cloud Stream to connect an application to an external messaging system (e.g., `txMessageOut-out-0`). | Messaging |
| **Shared Config** | Configuration files stored in Nacos (e.g., `actuator-config.yaml`) that are imported by multiple services to ensure consistency. | Configuration |
| **Distributed Transaction** | A transaction that spans multiple services, managed by the `framework-seata` module. | Data Consistency |

## 🛠️ Domain-Specific Terms

| Term | Definition | Related Component |
| :--- | :--- | :--- |
| **TxMessage** | A transaction-related message that may require reliable delivery and retry logic. | `system-module` |
| **TxMessageRetryTask** | A scheduled task that polls for pending `TxMessage` and attempts to redeliver them via `StreamBridge`. | `system-module` |
| **AuthToken** | A secure token (typically JWT) issued by the Auth service to authenticate subsequent requests. | `cloud-infra-auth` |
| **Management Port** | The dedicated port (default: 9301) used exclusively for health checks, metrics, and administrative endpoints. | Observability |

---
*Last Updated: 2026-07-13*
