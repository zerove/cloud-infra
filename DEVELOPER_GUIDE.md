# Developer Guide: cloud-infra

This guide provides the standard procedure for creating and integrating a new business module into the `cloud-infra` platform. Following these steps ensures that your service is consistent with the platform's observability, security, and communication standards.

## 🚀 Creating a New Module

### Step 1: Project Structure & Dependencies
All new modules must be placed under `cloud-infra-modules/` and use the `cloud-infra` parent POM to ensure version consistency.

**Required Dependencies in `pom.xml`:**
1.  **`cloud-infra-common`**: Must be included for basic utilities.
2.  **Framework Starters**: Include only the capabilities your service needs:
    *   `framework-web`: Required for any REST API (includes monitoring).
    *   `framework-mybatis`: For database access.
    *   `framework-redis`: For caching and distributed locking.
    *   `framework-security`: For integrating with the Auth service.
    *   `framework-tracing`: For distributed request tracing.

### Step 2: Configuration & Service Discovery
The platform uses **Nacos** for centralized configuration and discovery.

1.  **`bootstrap.yml`**: Ensure your service points to the correct Nacos namespace.
2.  **Shared Configs**: Import the standard actuator configuration to ensure your service is observable:
    ```yaml
    spring:
      cloud:
        nacos:
          config:
            shared-configs:
              - data-id: actuator-config.yaml
                refresh: true
    ```

### Step 3: Observability Standard
To comply with the "Actuator Engineering" standard:
*   **Port**: Management endpoints must run on port **9301**.
*   **Path**: Base path must be `/management`.
*   **Verification**: Once started, verify your health check:
    `curl http://localhost:9301/management/health` $\rightarrow$ Expected: `{"status":"UP"}`

### Step 4: Implementing Communication
Avoid synchronous HTTP calls between business modules whenever possible.

**Recommended Pattern: Event-Driven Messaging**
Use `StreamBridge` to dispatch events asynchronously:
```java
@Autowired 
private StreamBridge streamBridge;

public void notifyTransactionComplete(TransactionEvent event) {
    // Send to a specific binding defined in your application.yml
    streamBridge.send("transaction-out-0", event);
}
```

### Step 5: Data Access & Transactions
*   **DB Access**: Use `framework-mybatis` for consistent entity mapping.
*   **Distributed Transactions**: If your business process spans multiple modules, use the `framework-seata` integration to maintain atomicity.

---

## 🛠️ Checklist for Production Readiness
Before promoting a module to production, ensure the following:
- [ ] `CONTEXT.md` updated if new domain concepts were introduced.
- [ ] Management port 9301 is accessible and returning `UP`.
- [ ] Custom metrics are registered via `MetricsConfig` (if applicable).
- [ ] All external dependencies are managed via the parent POM.
- [ ] Distributed tracing is verified for all critical paths.
