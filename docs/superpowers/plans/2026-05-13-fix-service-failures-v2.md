# Fix Microservice Failures Implementation Plan (Revised)

**Goal:** Resolve remaining startup and runtime failures for cloud-gateway, cloud-search, cloud-notification, and cloud-system.

**Status Update:**
- `cloud-auth`: FIXED (Running on port 9201).
- `cloud-gateway`: STILL FAILING (NoClassDefFoundError).
- `cloud-search`: STILL FAILING (Connection Refused to Elasticsearch).
- `cloud-notification` & `cloud-system`: STILL FAILING (Connection Refused to RabbitMQ/Redis).

---

### Task 1: Fix Hostname Issues in Configurations

**Files:**
- Modify: `deploy/config/auth-application.yml`
- Modify: `deploy/config/notification-application.yml`
- Modify: `deploy/config/search-application.yml`
- Modify: `deploy/config/system-application.yml`

- [ ] **Step 1: Fix Redis host in auth-application.yml**
Change `spring.data.redis.host: localhost` to `redis-server`.

- [ ] **Step 2: Fix Redis/RabbitMQ hosts in notification-application.yml**
Change `localhost` defaults to `redis-server` and `rabbitmq-server`.

- [ ] **Step 3: Fix Elasticsearch host in search-application.yml**
Ensure it uses `elasticsearch:9200`.

- [ ] **Step 4: Fix Redis host in system-application.yml**
Change `localhost` to `redis-server`.

### Task 2: Fix cloud-gateway Dependency/Version Conflict

**Files:**
- Modify: `pom.xml`
- Modify: `cloud-infra-gateway/pom.xml`

- [ ] **Step 1: Downgrade Spring Boot to 3.4.x or 3.3.x**
Spring Boot 4.0.5 seems to be pre-release or highly experimental (Spring Boot 3.4.x is current stable). The class `org.springframework.boot.http.client.ClientHttpRequestFactorySettings` was introduced in 3.4, but `Redirects` might be slightly different or missing in the version of Gateway being used.
*Correction:* Actually, let's align all versions to stable Spring Boot 3.4.5 and Spring Cloud 2024.0.0.

- [ ] **Step 2: Align Gateway and Cloud versions**
Ensure `spring-cloud-starter-gateway-mvc` matches the Spring Cloud BOM version.

### Task 3: Redeploy and Validate

- [ ] **Step 1: Rebuild**
`mvn clean install -DskipTests`

- [ ] **Step 2: Update Nacos Configs**
`cd deploy && ./up.sh` (The script syncs configs).

- [ ] **Step 3: Verify**
Check health endpoints and logs.
