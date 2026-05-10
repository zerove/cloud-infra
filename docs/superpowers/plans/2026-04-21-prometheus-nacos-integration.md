# Prometheus & Nacos Integration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Prometheus into the infrastructure, leveraging Nacos for dynamic service discovery of monitored targets.

**Architecture:** 
- Add `prometheus` service to `docker-compose.yaml`.
- Configure Prometheus to use Nacos SD (Service Discovery) to automatically discover services registered in Nacos.
- Expose `/management/prometheus` endpoint (already enabled in previous task).

**Tech Stack:** Prometheus, Nacos.

---

### Task 1: Update Docker Compose Configuration
**Files:**
- Modify: `deploy/compose.yaml`

- [ ] **Step 1: Add Prometheus service definition**

Add to `deploy/compose.yaml`:
```yaml
  prometheus:
    image: prom/prometheus:latest
    container_name: prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    networks:
      - deploy_default
```

### Task 2: Create Prometheus Configuration with Nacos SD
**Files:**
- Create: `deploy/prometheus.yml`

- [ ] **Step 1: Create Prometheus configuration**

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'nacos-sd'
    metrics_path: '/management/prometheus'
    nacos_sd_configs:
      - server_addr: 'nacos-server:8848'
        refresh_interval: 30s
    relabel_configs:
      - source_labels: [__meta_nacos_service_name]
        target_label: job
```

### Task 3: Deploy and Verify
- [ ] **Step 1: Restart infrastructure**
Run: `docker-compose -f deploy/compose.yaml up -d prometheus`

- [ ] **Step 2: Verify service discovery**
Open Prometheus UI at `http://localhost:9090`, check "Status -> Targets".
Expected: All registered services (system-module, etc.) should appear with status UP.

---

**Plan complete and saved to `docs/superpowers/plans/2026-04-21-prometheus-nacos-integration.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
