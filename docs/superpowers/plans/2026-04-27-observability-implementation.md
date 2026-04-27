# Observability and Prometheus Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Configure Spring Boot Actuator with Prometheus metrics for all modules and set up basic monitoring infrastructure.

**Architecture:** Utilize Spring Boot Actuator endpoints for metric exposure and ensure compatibility with Prometheus scrape targets. Configure centralized monitoring settings via application properties or dedicated configuration classes.

**Tech Stack:** Spring Boot Actuator, Micrometer Prometheus Registry.

---

### Task 1: Configure Actuator and Prometheus Metrics

**Files:**
- Modify: `cloud-infra-common/src/main/resources/application.yml` (Create if not exists)
- Modify: `cloud-infra-gateway/src/main/resources/application.yml`
- Modify: `cloud-infra-auth/src/main/resources/application.yml`

- [ ] **Step 1: Define Actuator/Prometheus properties in common module**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus,health,info
  metrics:
    export:
      prometheus:
        enabled: true
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-common/src/main/resources/application.yml
git commit -m "feat: add actuator and prometheus configuration"
```

### Task 2: Verify Actuator Endpoint

**Files:**
- Modify: `cloud-infra-gateway/src/test/java/com/cloudinfra/gateway/GatewayActuatorTest.java` (Create)

- [ ] **Step 1: Write integration test to verify actuator endpoint**

```java
package com.cloudinfra.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class GatewayActuatorTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldReturnPrometheusMetrics() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn test -pl cloud-infra-gateway -Dtest=GatewayActuatorTest`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add cloud-infra-gateway/src/test/java/com/cloudinfra/gateway/GatewayActuatorTest.java
git commit -m "test: verify actuator prometheus endpoint"
```
