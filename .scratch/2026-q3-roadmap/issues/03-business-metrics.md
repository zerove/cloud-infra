# M5-03: 业务告警指标接入 (Micrometer Counter)

Status: closed (done 2026-09-01)
Priority: P0
Owner: AI Agent
Module: cloud-infra-framework/framework-web + cloud-infra-auth + cloud-infra-modules/file-service
Depends: 无

## 背景

11 条 Prometheus 告警规则中, 2 条业务类指标 (`auth_login_attempts_total` + `file_upload_failures_total`) 业务代码从未实现 (0 处 Micrometer Counter 用法).

不修复 → LoginFailureRateHigh / FileUploadFailureHigh 告警永不触发, v1.0.0 "告警闭环" 实际缺失.

## 改动 (已提交 commit 8430811)

### 1. 框架层 (cloud-infra-framework/framework-web)

**新增** `BusinessMetric` 注解:
- metric: Prometheus 指标名
- reason: 默认 tag 值
- resultOnSuccess: 启用成功/失败分桶
- resultOnFailure: 失败时 tag 值

**新增** `BusinessMetricAspect` 切面:
- @Around: 拦截方法返回 `Result<?>` 时自动判断 success/failure
- @AfterThrowing: 方法抛异常时 increment 失败 Counter, 异常原样传播
- 按 (metric, tagKey, tagValue) 缓存 Counter, 避免重复 register

**改写** `MetricsConfig`:
- 移除 3 个硬编码 Counter Bean (AOP 动态创建)
- 保留 `metricsCommonTags` 项目级公共标签

### 2. 业务模块接入

**auth/SysLoginService.login()**:
```java
@BusinessMetric(metric = "auth.login.attempts",
                reason = "login",
                resultOnSuccess = "success",
                resultOnFailure = "failure")
public Result<Map<String, Object>> login(LoginUser loginUser) { ... }
```

**file/FileServiceImpl.upload()**:
```java
@BusinessMetric(metric = "file.upload.failures", reason = "upload")
public FileMetadataVO upload(Long userId, FileContent content) throws IOException { ... }
```

### 3. 单元测试

`BusinessMetricAspectTest`: 8 个测试覆盖
- Counter 按需创建 + 复用
- Result.success / Result.fail 自动分桶
- 异常原样传播

## 关键设计: AOP 化避免 try-catch

项目 adg 规则 (`java-catch-all`) 禁止业务方法写 `catch (Exception e)`, 但 AOP 的 @Around + @AfterThrowing 是合规的:
- @Around 不需要 catch 块, 直接 `proceed()`
- @AfterThrowing 通过 `throwing = "ex"` 参数接收异常, 框架自动 re-throw
- 业务代码无 try-catch, 符合 adg 规则

## 验证

- mvn test framework-web: **13 tests, 0 fail** (5 原有 + 8 新增)
- mvn test auth: 27 tests, 0 fail
- mvn test file-service: 36 tests, 0 fail
- mvn test codegen-core: 25 tests, 0 fail (无回归)

## 关于 catch (Exception) 警告

adg check 在以下位置仍报 catch-all error, 但均为 master 原状业务设计:
- `SysLoginService line 86`: 业务故意兜底返回通用错误码 (`Result.fail`)
- `FileServiceImpl line 114`: 事务回滚后清理孤儿文件的兜底

未引入新的 `catch (Exception)` 块. 此问题属于 P3 卫生项, 留给未来重构 (项目有 58 个文件触发同一规则).

## 对应 Prometheus 告警

- `LoginFailureRateHigh` (deploy/monitoring/prometheus-rules/cloud-infra-alerts.yml:115)
  - `sum by (application) (rate(auth_login_attempts_total{result="failure"}[5m])) / sum by (application) (rate(auth_login_attempts_total[5m])) > 0.5`
- `FileUploadFailureHigh` (line 124)
  - `rate(file_upload_failures_total[5m]) > 0.1`

## 引用

- commit: 8430811
- 设计: 框架层 (framework-web) 暴露注解, 业务模块按需标注
- 与 M5-04 报告 (`docs/codegen/2026-09-01-gray-migrate-sys-post.md`) 关联: 未来 codegen 模板可自动生成 `@BusinessMetric` 注解
