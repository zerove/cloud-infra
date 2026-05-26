# Phase 3 & 4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate MinIO object storage into `file-service`, validate Seata distributed transactions, and optimize container native builds.

**Architecture:** 
- MinIO: Add `MinioFileService` implementing `IFileService`, with strategy pattern support.
- Seata: Enable `@GlobalTransactional` in `system-module` (Dept/User operations) and `auth-service`, add integration tests.
- Native: Verify `Dockerfile.native` build with GraalVM.

**Tech Stack:** MinIO Java SDK, Seata 1.7+, Spring Boot 4, GraalVM.

---

### Task 1: MinIO Integration

**Files:**
- Modify: `cloud-infra-modules/file-service/pom.xml`
- Create: `cloud-infra-modules/file-service/src/main/java/com/cloudinfra/file/service/MinioFileService.java`
- Modify: `cloud-infra-modules/file-service/src/main/java/com/cloudinfra/file/service/LocalFileService.java`

- [ ] **Step 1: Add MinIO SDK to `file-service/pom.xml`**
```xml
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>8.5.7</version>
</dependency>
```

- [ ] **Step 2: Implement MinioFileService**
(Implement `upload`, `download`, `delete` based on `IFileService` interface)

### Task 2: Distributed Transaction Verification

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/DeptServiceImpl.java`
- Modify: `cloud-infra-test-support/src/main/java/com/cloudinfra/test/BaseIntegrationTest.java`

- [ ] **Step 1: Apply `@GlobalTransactional` to test service method**
- [ ] **Step 2: Write integration test case simulating cross-db failure**

### Task 3: Native Image Verification

- [ ] **Step 1: Verify `Dockerfile.native` build process**
Run: `docker build -f deploy/Dockerfile.native -t cloud-infra-system:native .`

---
