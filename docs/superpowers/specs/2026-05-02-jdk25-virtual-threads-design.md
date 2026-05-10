# Design: High-Performance Gateway MVC with JDK 25 Virtual Threads

## 1. Overview
Optimize the Spring Cloud Gateway MVC application (Spring Boot 4.0.6, Spring Cloud 4.1.0) to leverage JDK 25 Virtual Threads for high throughput and low latency.

## 2. Goals
- Maximize concurrency handling using Virtual Threads.
- Minimize I/O blocking overhead in downstream calls.
- Provide observability for Virtual Thread pinning events.
- Optimize JVM for virtual thread workloads.

## 3. Architecture & Components

### 3.1 Web Server Configuration
- Spring Boot 4.0.6 automatically configures Tomcat to use virtual threads when `spring.threads.virtual.enabled` is `true`.
- Incoming servlet requests are handled by virtual threads, allowing the server to handle thousands of concurrent connections with minimal memory overhead.

### 3.2 Downstream I/O (RestClient)
- **Problem**: Default HTTP clients might not be optimized for the high concurrency enabled by virtual threads.
- **Solution**: Configure `HttpComponentsClientHttpRequestFactory` with Apache HttpClient 5.
- **Reasoning**: Virtual threads thrive on blocking I/O. Apache HttpClient 5 in blocking mode is simple and highly efficient when running on virtual threads.
- **Tuning**:
    - `maxConnTotal`: 2000
    - `maxConnPerRoute`: 500
    - `connectionRequestTimeout`: 5s

### 3.3 Observability (Pinned Thread Detection)
- **Problem**: `synchronized` blocks or native calls can "pin" a virtual thread to its carrier thread, reducing performance.
- **Solution**: 
    - Implement a `PinnedThreadMonitor` using JDK 25 JFR events.
    - Capture `jdk.VirtualThreadPinned` events.
    - Export metrics via Micrometer (`gateway.threads.pinned.count`).

### 3.4 Task Execution
- Implement a global `VirtualThreadTaskExecutor` bean in `framework-web` to ensure all `@Async` tasks and internal background processing use virtual threads.

## 4. Implementation Plan

### Step 1: Framework Web Enhancements
- Update `VirtualThreadConfig.java` to provide `AsyncTaskExecutor`.
- Implement `PinnedThreadMetricAppender` or a JFR-based monitor.

### Step 2: Gateway Configuration
- Customize `RestClient` bean in `GatewayApplication` or a config class.
- Add Apache HttpClient 5 dependency if missing (it should be part of Boot 4 starter-web).

### Step 3: JVM Tuning
- Provide a set of recommended JVM flags for JDK 25.

## 5. Success Criteria
- Gateway handles high concurrent requests without thread exhaustion.
- Pinned thread events are visible in Prometheus metrics.
- Low latency (p99) maintained during high load.
