# SkyWalking Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Apache SkyWalking 9.7.0 for distributed tracing in the cloud-infra project.

**Architecture:** Add `oap` and `ui` services to `compose.yaml`. Configure `Dockerfile` to inject the SkyWalking Java Agent. Pass `SW_AGENT_COLLECTOR_BACKEND_SERVICES` and `SW_AGENT_NAME` environment variables to microservices.

**Tech Stack:** SkyWalking 9.7.0, Docker, Java Agent.

---

### Task 1: Update Dockerfile for SkyWalking Agent

**Files:**
- Modify: `deploy/Dockerfile`

- [ ] **Step 1: Update Dockerfile to download and include the SkyWalking agent**

```dockerfile
# Stage: Run
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app

# Create a non-root user
RUN groupadd -g 1000 appuser && \
    useradd -u 1000 -g appuser -m appuser

# Download SkyWalking Agent
RUN mkdir -p /app/skywalking-agent && \
    curl -L https://archive.apache.org/dist/skywalking/java-agent/9.2.0/apache-skywalking-java-agent-9.2.0.tgz | tar -xz -C /app/skywalking-agent --strip-components=1

# Copy the jar from the host target directory
ARG JAR_FILE
COPY ${JAR_FILE} app.jar

RUN chown -R appuser:appuser /app
USER appuser

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-Xms512m -Xmx512m -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java -javaagent:/app/skywalking-agent/skywalking-agent.jar ${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
```

- [ ] **Step 2: Commit**

```bash
git add deploy/Dockerfile
git commit -m "feat: configure skywalking agent in Dockerfile"
```

### Task 2: Update compose.yaml

**Files:**
- Modify: `deploy/compose.yaml`

- [ ] **Step 1: Add OAP and UI services to compose.yaml**

```yaml
  oap:
    image: apache/skywalking-oap-server:9.7.0
    container_name: oap
    ports:
      - "11800:11800"
      - "12800:12800"
    restart: always

  ui:
    image: apache/skywalking-ui:9.7.0
    container_name: skywalking-ui
    environment:
      - SW_OAP_ADDRESS=http://oap:12800
    ports:
      - "8080:8080"
    depends_on:
      - oap
    restart: always
```

- [ ] **Step 2: Update microservice environment variables**

Update `gateway`, `auth`, `system` services to include:

```yaml
    environment:
      ...
      - SW_AGENT_COLLECTOR_BACKEND_SERVICES=oap:11800
      - SW_AGENT_NAME=cloud-gateway # Use unique names for each service
```

- [ ] **Step 3: Commit**

```bash
git add deploy/compose.yaml
git commit -m "feat: add skywalking oap/ui and configure agent env vars"
```
