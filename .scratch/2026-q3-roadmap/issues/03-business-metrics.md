# M5-03: 业务告警指标接入 (Micrometer Counter)

Status: open
Priority: P0
Owner: TBD
Module: cloud-infra-auth + cloud-infra-modules/file-service
Depends: 无

## 背景

11 条 Prometheus 告警规则中,2 条业务类告警的指标**尚未实现**:
- `auth_login_attempts_total` (auth 模块登录失败计数器) — 触发 `AuthLoginFailureSpike` 告警
- `file_upload_failures_total` (file 模块上传失败计数器) — 触发 `FileUploadFailureSpike` 告警

见 `deploy/monitoring/prometheus-rules/cloud-infra-alerts.yml`。

## 任务

### 1. auth 模块登录指标

文件: `cloud-infra-auth/src/main/java/.../service/impl/AuthServiceImpl.java` (或类似)

```java
// 在 AuthService 注册 Micrometer Counter
@Autowired private MeterRegistry meterRegistry;
private Counter loginSuccessCounter;
private Counter loginFailureCounter;

@PostConstruct
public void init() {
    this.loginSuccessCounter = Counter.builder("auth.login.attempts")
        .tag("result", "success").register(meterRegistry);
    this.loginFailureCounter = Counter.builder("auth.login.attempts")
        .tag("result", "failure").register(meterRegistry);
}

// 登录成功/失败处调对应 counter
loginSuccessCounter.increment(); // 或 loginFailureCounter.increment();
```

### 2. file 模块上传指标

文件: `cloud-infra-modules/file-service/src/main/java/.../FileServiceImpl.java`

类似模式,Counter `file.upload.failures`,tag `reason`。

### 3. 验证

```bash
# 启动后,登录失败 3 次
curl -X POST http://localhost:8081/auth/login -d '{"username":"x","password":"x","captcha":"x","uuid":"x"}'
# 重复 3 次

# 抓取指标
curl http://localhost:9301/management/prometheus | grep auth_login_attempts
# 期望: auth_login_attempts_total{result="failure"} 3.0

# 触发告警
# 5 分钟内失败 > 10 次, 触发 AuthLoginFailureSpike
```

## 验收

- [ ] `auth_login_attempts_total{result="success|failure"}` 出现在 Prometheus
- [ ] `file_upload_failures_total{reason=...}` 出现在 Prometheus
- [ ] 故意触发失败,验证告警规则匹配

## 参考

- 告警规则: `deploy/monitoring/prometheus-rules/cloud-infra-alerts.yml`
- Micrometer 文档: https://docs.micrometer.io/micrometer/reference/concepts/counters.html
- 已有业务指标范例: `cloud-infra-modules/notification-service` (msg_sent_total)
