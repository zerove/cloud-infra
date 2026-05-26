# Deployment and Verification Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix deployment scripts, build project, and verify successful startup of all microservices.

**Architecture:** Use `podman-compose` to orchestrate infrastructure (Nacos, Redis, PostgreSQL) and microservices. Fix `Dockerfile.jre` to handle multiple services correctly.

**Tech Stack:** Maven, Podman, Spring Boot, Nacos.

---

### Task 1: Fix `deploy/Dockerfile.jre`

**Files:**
- Modify: `deploy/Dockerfile.jre`

- [ ] **Step 1: Update Dockerfile to use build argument for JAR path**

```dockerfile
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
ARG JAR_FILE
COPY ${JAR_FILE} app.jar
ENV JAVA_OPTS="-XX:+UseZGC -Xms256m -Xmx512m -Dspring.threads.virtual.enabled=true"
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Task 2: Complete `deploy/compose.yaml`

**Files:**
- Modify: `deploy/compose.yaml`

- [ ] **Step 1: Add missing services and use build arguments for JAR files**

```yaml
services:
  nacos:
    image: nacos/nacos-server:v2.3.2
    container_name: nacos-server
    environment:
      - MODE=standalone
    ports:
      - "8848:8848"
  
  postgres:
    image: postgres:16-alpine
    container_name: postgres
    environment:
      - POSTGRES_PASSWORD=password
      - POSTGRES_DB=cloud_infra
    ports:
      - "5432:5432"

  redis:
    image: redis:7-alpine
    container_name: redis
    ports:
      - "6379:6379"

  gateway:
    build:
      context: ..
      dockerfile: deploy/Dockerfile.jre
      args:
        JAR_FILE: cloud-infra-gateway/target/cloud-infra-gateway-1.0.0-SNAPSHOT.jar
    container_name: cloud-gateway-1
    ports:
      - "9000:9000"
    depends_on:
      - nacos

  auth:
    build:
      context: ..
      dockerfile: deploy/Dockerfile.jre
      args:
        JAR_FILE: cloud-infra-auth/target/cloud-infra-auth-1.0.0-SNAPSHOT.jar
    container_name: auth-service
    ports:
      - "9200:9200"
    depends_on:
      - nacos

  system:
    build:
      context: ..
      dockerfile: deploy/Dockerfile.jre
      args:
        JAR_FILE: cloud-infra-modules/system-module/target/system-module-1.0.0-SNAPSHOT.jar
    container_name: system-service
    ports:
      - "9300:9300"
    depends_on:
      - nacos
      - postgres
      - redis

  notification:
    build:
      context: ..
      dockerfile: deploy/Dockerfile.jre
      args:
        JAR_FILE: cloud-infra-modules/notification-service/target/notification-service-1.0.0-SNAPSHOT.jar
    container_name: notification-service
    depends_on:
      - nacos

  search:
    build:
      context: ..
      dockerfile: deploy/Dockerfile.jre
      args:
        JAR_FILE: cloud-infra-modules/search-service/target/search-service-1.0.0-SNAPSHOT.jar
    container_name: search-service
    depends_on:
      - nacos
```

### Task 3: Build the Project

**Files:**
- Command line

- [ ] **Step 1: Run Maven build**

Run: `mvn clean install -DskipTests`
Expected: BUILD SUCCESS

### Task 4: Deploy and Verify

**Files:**
- Command line

- [ ] **Step 1: Run `deploy/up.sh`**

Run: `cd deploy && ./up.sh`
Expected: "部署完成！" message and all services running.

- [ ] **Step 2: Verify services are up**

Run: `podman ps` and check logs if necessary.
Verify Nacos config: `curl http://localhost:8848/nacos/v1/cs/configs?dataId=system-module.yaml&group=DEFAULT_GROUP`
Verify Gateway: `curl -I http://localhost:9000/actuator/health` (assuming actuator is enabled)
