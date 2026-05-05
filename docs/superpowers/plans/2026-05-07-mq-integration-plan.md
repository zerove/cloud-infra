# MQ Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement reliable transaction message retry using Spring Cloud Stream and RabbitMQ to improve system reliability.

**Architecture:**
- Use **Spring Cloud Stream** with `StreamBridge` for decoupling and architectural consistency.
- **Message Broker:** RabbitMQ.
- **Reliability:** `Publisher Confirms` mechanism.
- **Message Format:** `CloudMessage<T>` wrapper to include `traceId`.

**Tech Stack:**
- Java 25
- Spring Cloud Stream
- RabbitMQ
- Testcontainers (for integration tests)

---

### Task 1: Create `CloudMessage<T>`

**Files:**
- Create: `cloud-infra-common/src/main/java/com/cloudinfra/common/core/messaging/CloudMessage.java`

- [ ] **Step 1: Create `CloudMessage` class**

```java
package com.cloudinfra.common.core.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CloudMessage<T> implements Serializable {
    private String traceId;
    private T data;
}
```

### Task 2: Configure Spring Cloud Stream in `system-module`

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/resources/application.yml`

- [ ] **Step 1: Add RabbitMQ binder configuration**

```yaml
spring:
  cloud:
    stream:
      bindings:
        txMessageOut-out-0:
          destination: tx.message.exchange
          content-type: application/json
      rabbit:
        bindings:
          txMessageOut-out-0:
            producer:
              publisher-confirm-type: correlated
```

### Task 3: Refactor `TxMessageRetryTask` to use `StreamBridge`

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/task/TxMessageRetryTask.java`

- [ ] **Step 1: Inject `StreamBridge`**
- [ ] **Step 2: Update retry logic to use `StreamBridge.send("txMessageOut-out-0", CloudMessage.builder()...build())`**

### Task 4: Add Integration Test

**Files:**
- Create: `cloud-infra-modules/system-module/src/test/java/com/cloudinfra/system/task/TxMessageRetryTaskTest.java`

- [ ] **Step 1: Create Test Class with RabbitMQContainer**
- [ ] **Step 2: Implement test verifying message sending**
- [ ] **Step 3: Run integration test**
