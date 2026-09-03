# v1.0.0 完整重新部署 + 端到端验证报告 (2026-09-03)

**验证时间**: 2026-09-03 06:40 ~ 06:59 (CST, ~20 分钟)
**基线 commit**: `da7c3fe` (HEAD, 含 v1.0.0 tag `e6b786d` immutable)
**验证人**: AI Agent
**状态**: ✅ **v1.0.0 重新部署 + P0-3b 401 + M5-03 Counter 全部 PASS**

---

## 1. 流程

| 步骤 | 内容 | 结果 |
|---|---|---|
| 1 | 清理 9 个非核心容器 (monitoring + auth) | ✅ 7 核心基础设施保留 |
| 2 | mvn clean install -DskipTests (全量构建) | ✅ BUILD SUCCESS (2m 58s) |
| 3 | 启动 6 个业务模块 exec.jar 后台 | ✅ 5/6 UP (search 因 ES repository 配置问题未启动) |
| 4 | 端到端 P0-3b 401 + M5-03 Counter 验证 | ✅ 全部 PASS |

---

## 2. Step 1: 容器清理

### 保留 (7 核心基础设施)
- cloud-postgres: Up 42h (healthy)
- cloud-redis: Up 42h (healthy)
- cloud-rabbitmq: Up 42h (healthy)
- cloud-elasticsearch: Up 42h (healthy)
- cloud-sentinel: Up 42h (healthy)
- cloud-minio: Up 42h (healthy)
- cloud-nacos: Up 42h (healthy)

### 删除 (9 个)
- cloud-prometheus, cloud-alertmanager, cloud-loki, cloud-otel-collector, cloud-jaeger
- cloud-grafana, cloud-redisinsight, cloud-promtail
- cloud-skywalking-oap, cloud-skywalking-ui, cloud-auth

### 资源
- 内存: 19Gi total, 4.5Gi used, **15Gi available**
- 磁盘: 712G available (26% used)

---

## 3. Step 2: 全量构建

```bash
$ mvn clean install -DskipTests -T 1
[INFO] BUILD SUCCESS (2m 58s)
```

6 个 exec.jar 全部生成:
- cloud-infra-gateway: 109 MB
- cloud-infra-auth: 108 MB
- cloud-infra-modules/system-module: 135 MB
- cloud-infra-modules/file-service: 128 MB
- cloud-infra-modules/notification-service: 155 MB
- cloud-infra-modules/search-service: 141 MB

---

## 4. Step 3: 业务模块启动 (后台进程)

用 `nohup java -jar -exec.jar --server.port=...` 启动,环境变量传 Nacos / Redis / RabbitMQ / Postgres / ES 地址:

```bash
ENV_BASE="SPRING_CLOUD_NACOS_CONFIG_SERVER_ADDR=localhost:8848 \
  SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR=localhost:8848 \
  AUTH_JWT_SECRET=... AUTH_INTERNAL_CALL_SECRET=... INTERNAL_CALL_SECRET=... \
  SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/cloud_infra \
  SPRING_DATA_REDIS_HOST=localhost SPRING_RABBITMQ_HOST=localhost \
  SPRING_ELASTICSEARCH_URIS=http://localhost:9200"
```

### 启动结果

| 模块 | 启动时间 | 业务端口 | mgmt 端口 | 状态 |
|---|---|---|---|---|
| gateway | 短 (~60s) | 9000 → **401** ✓ | 9301 → 404 ✓ | UP, security filter 工作 |
| auth | **191 秒** | 8081 → 200 ✓ | 9302 → 200 ✓ | UP, AuthApplication started |
| system | **225 秒** (2GB heap) | 8082 → 200 ✓ | 9303 → 200 ✓ | UP, SystemApplication started |
| file | 186 秒 | 8083 → 200 ✓ | 9304 → 200 ✓ | UP, FileApplication started |
| notification | 217 秒 | 8084 → 200 ✓ | 9305 → 200 ✓ | UP, NotificationApplication started |
| search | - | 8085 → ✗ | 9306 → ✗ | **失败**: ES repository 配置问题 (P3-15 已知) |

**5/6 业务模块成功启动**。search 因 ES repository 配置问题未启动,与 P0-3b / M5-03 验证无关。

---

## 5. Step 4: 端到端 P0-3b 验证 (关键)

| 步骤 | 命令 | 期望 | 实际 |
|---|---|---|---|
| 1 | `GET http://localhost:8081/auth/captchaImage` | 200 + UUID | ✅ UUID=74614438-... |
| 2 | `POST /auth/login` admin/admin123 (captcha=1234) | 200 + token | ✅ TOKEN length 236 |
| 3 | `GET http://localhost:8082/system/user/info -H "Bearer $TOKEN"` | 200 | ✅ HTTP 200 |
| 4 | `POST /auth/logout` | 200 + 写 blacklist | ✅ HTTP 200 |
| **5** | **`GET /system/user/info` 用已登出 token** | **HTTP 401** | **✅ HTTP 401 + `X-Blacklist-Jti: 5b195b70-...`** |

---

## 6. Step 5: M5-03 业务告警 Counter 验证

```bash
# 跑 5 次故意失败登录
$ for i in 1..5; do POST /auth/login admin/WRONG_$i; done
```

### Prometheus 抓取结果 (auth /actuator/prometheus)

```prometheus
# HELP auth_login_attempts_total 业务方法异常计数器 (M5-03, AfterThrowing 实现)
# TYPE auth_login_attempts_total counter
auth_login_attempts_total{application="cloud-auth",project="cloud-infra",result="failure"} 5.0
auth_login_attempts_total{application="cloud-auth",project="cloud-infra",result="success"} 1.0
```

**关键证据**:
- ✅ 公共标签 `application=cloud-auth`, `project=cloud-infra` (MetricsConfig 生效)
- ✅ 业务标签 `result=failure` (5 次失败) + `result=success` (1 次成功)
- ✅ 数值精确: failure=5.0, success=1.0
- ✅ AOP 切面 (`@BusinessMetric` 注解 + `@Around`) 端到端工作

---

## 7. 已知问题 (本次未修)

- **search-service ES repository 配置问题** (`@EnableElasticsearchRepositories` 启动失败): 与 P3-15 报告一致, 需 search-service 自身 ES 配置修复
- **容器 skywalking-oap 仍 Exited** (P3-14 跟踪中): 镜像 9.7.0 vs application.yml 11.0.0 不匹配, 需升级 11.0.0 (网络阻塞)
- **cloud-auth 容器仍 Exited** (本次清理删了, 留 .scratch issue 17 跟踪)

## 8. 引用

- M5-08 重构 (commit 48ebbf9): codegen 模板整合
- P3-15 e2e 报告 (commit 9a15a52): 业务告警 Counter + 端到端
- SESSION-SUMMARY-2026-09-01.md: 本会话前 v1.0.0 零信任发布
