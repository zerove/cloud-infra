# Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement full-link tracing (SkyWalking/Zipkin) and metrics collection (Prometheus) for the microservices.

**Architecture:** Integrate tracing filters (web) and actuator metrics (Micrometer) into the `cloud-infra-framework`.

**Tech Stack:** Java, Spring Boot, Spring Cloud, Micrometer, SkyWalking.

---

### Task 1: Add Micrometer Metrics Support

**Files:**
- Modify: `cloud-infra-framework/framework-tracing/pom.xml`

- [ ] **Step 1: Add micrometer dependencies**
(Add micrometer-registry-prometheus to framework-tracing)

- [ ] **Step 2: Commit**
`git add . && git commit -m "feat: add micrometer prometheus support"`

### Task 2: Configure Global Metrics/Tracing

**Files:**
- Create/Modify: `cloud-infra-framework/framework-tracing/src/main/java/com/cloudinfra/framework/tracing/config/TracingConfig.java`

- [ ] **Step 1: Implement configuration class for tracing**

- [ ] **Step 2: Commit**
`git commit -am "feat: implement global tracing configuration"`
