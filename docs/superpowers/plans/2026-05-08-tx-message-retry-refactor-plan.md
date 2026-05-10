# TxMessageRetryTask Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `TxMessageRetryTask` to use `StreamBridge` for asynchronous message processing.

**Architecture:** Integrate `StreamBridge` into `TxMessageRetryTask` to dispatch messages to `txMessageOut-out-0`.

**Tech Stack:** Spring Cloud Stream, Java.

---

### Task 1: Update TxMessageRetryTask

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/task/TxMessageRetryTask.java`

- [ ] **Step 1: Inject StreamBridge**

Add `@Autowired private StreamBridge streamBridge;` to `TxMessageRetryTask`.

- [ ] **Step 2: Update retry logic**

Replace the TODO with:
```java
streamBridge.send("txMessageOut-out-0", message);
```

- [ ] **Step 3: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/task/TxMessageRetryTask.java
git commit -m "feat: refactor TxMessageRetryTask to use StreamBridge"
