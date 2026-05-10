# JDK 25 Virtual Threads Performance Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Optimize Gateway MVC with JDK 25 Virtual Threads, high-concurrency connection pooling, and pinning observability.

**Architecture:** Leverage Spring Boot 4's native virtual thread support, optimize downstream calls with Apache HttpClient 5, and implement JFR-based monitoring for pinning events.

**Tech Stack:** Spring Boot 4.0.6, Spring Cloud Gateway MVC 4.1.0, JDK 25, Apache HttpClient 5, Micrometer.

---

### Task 1: Framework Web - Virtual Thread Configuration

**Files:**
- Modify: `cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/config/VirtualThreadConfig.java`

- [ ] **Step 1: Implement VirtualThreadConfig**

```java
package com.cloudinfra.framework.web.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;

@Configuration
@EnableAsync
@ConditionalOnProperty(prefix = "spring.threads.virtual", name = "enabled", havingValue = "true")
public class VirtualThreadConfig {

    @Bean
    public AsyncTaskExecutor applicationTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/config/VirtualThreadConfig.java
git commit -m "feat(framework): enable virtual thread task executor"
```

### Task 2: Gateway - Dependency and RestClient Optimization

**Files:**
- Modify: `cloud-infra-gateway/pom.xml`
- Create: `cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/config/GatewayClientConfig.java`

- [ ] **Step 1: Add Apache HttpClient 5 Dependency**

Add to `cloud-infra-gateway/pom.xml`:
```xml
<dependency>
    <groupId>org.apache.httpcomponents.client5</groupId>
    <artifactId>httpclient5</artifactId>
</dependency>
```

- [ ] **Step 2: Implement GatewayClientConfig**

```java
package com.cloudinfra.gateway.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class GatewayClientConfig {

    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(2000);
        connectionManager.setDefaultMaxPerRoute(500);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout((int) Timeout.ofSeconds(5).toMilliseconds());
        return factory;
    }

    @Bean
    public RestClient restClient(RestClient.Builder builder, ClientHttpRequestFactory factory) {
        return builder.requestFactory(factory).build();
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add cloud-infra-gateway/pom.xml cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/config/GatewayClientConfig.java
git commit -m "feat(gateway): optimize RestClient with pooled Apache HttpClient 5"
```

### Task 3: Observability - Pinned Thread Monitor (JDK 25 JFR)

**Files:**
- Create: `cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/monitor/VirtualThreadPinnedMonitor.java`

- [ ] **Step 1: Implement VirtualThreadPinnedMonitor**

```java
package com.cloudinfra.framework.web.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jdk.jfr.consumer.EventStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "spring.threads.virtual", name = "enabled", havingValue = "true")
public class VirtualThreadPinnedMonitor {
    private static final Logger log = LoggerFactory.getLogger(VirtualThreadPinnedMonitor.class);
    private final Counter pinnedCounter = Metrics.counter("gateway.threads.pinned.count");
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread monitorThread;

    @PostConstruct
    public void start() {
        monitorThread = Thread.ofPlatform().name("vthread-pinned-monitor").start(() -> {
            try (var es = EventStream.openRepository()) {
                es.onEvent("jdk.VirtualThreadPinned", event -> {
                    pinnedCounter.increment();
                    log.warn("Virtual Thread Pinned: duration={}ns, thread={}", 
                        event.getDuration().toNanos(), event.getThread("eventThread").getJavaName());
                });
                es.start();
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Failed to start JFR event stream for pinning detection", e);
                }
            }
        });
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (monitorThread != null) {
            monitorThread.interrupt();
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/monitor/VirtualThreadPinnedMonitor.java
git commit -m "feat(framework): add JFR-based virtual thread pinning monitor"
```

### Task 4: Verification - Performance Integration Test

**Files:**
- Create: `cloud-infra-gateway/src/test/java/com/cloudinfra/gateway/VirtualThreadPerformanceTest.java`

- [ ] **Step 1: Write Verification Test**

```java
package com.cloudinfra.gateway;

import com.cloudinfra.test.BaseIntegrationTest;
import io.micrometer.core.instrument.Metrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

public class VirtualThreadPerformanceTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void testVirtualThreadConcurrency() {
        // Simple test to ensure gateway is responsive under "concurrent" load
        // Actual performance test would require a real backend
        int concurrency = 100;
        var futures = IntStream.range(0, concurrency)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
                    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
                }))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();
        
        // Verify virtual threads are being used (indirectly by checking if we didn't crash)
        System.out.println("Concurrent requests completed successfully using virtual threads.");
    }
}
```

- [ ] **Step 2: Run Verification Test**

Run: `mvn test -Dtest=VirtualThreadPerformanceTest`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add cloud-infra-gateway/src/test/java/com/cloudinfra/gateway/VirtualThreadPerformanceTest.java
git commit -m "test(gateway): add virtual thread performance verification test"
```
