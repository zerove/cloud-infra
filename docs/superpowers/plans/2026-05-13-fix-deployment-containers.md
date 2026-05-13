# Fix Deployment Container Errors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve "no container found" errors for rabbitmq-server, cloud-search, and cloud-notification by including them in the `up.sh` deployment script.

**Architecture:** Update the deployment script `up.sh` to include missing services defined in `compose.yaml`. This ensures all platform components are managed correctly during startup and shutdown.

**Tech Stack:** Shell Script, Podman Compose

---

### Task 1: Update up.sh to include missing services

**Files:**
- Modify: `deploy/up.sh`

- [ ] **Step 1: Add rabbitmq to middleware startup**
Modify L29 to include `rabbitmq`.

- [ ] **Step 2: Add notification and search to business services startup**
Modify L45 to include `notification` and `search`.

- [ ] **Step 3: Verify the changes**
Review the script to ensure the service names match those in `compose.yaml`.

### Task 2: Validate the fix

- [ ] **Step 1: Run up.sh**
Run the script and check for errors.
Command: `cd deploy && ./up.sh`

- [ ] **Step 2: Verify containers are running**
Command: `podman ps`
Expected: `rabbitmq-server`, `cloud-notification`, and `cloud-search` should be in the list.

- [ ] **Step 3: Verify prometheus discovery**
Check `deploy/services.json` to see if the new services are included.
