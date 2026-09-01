# P3-04: skywalking-oap 配置路径问题 (config 找不到)

Status: open
Priority: P3
Owner: TBD
Module: deploy/skywalking/
Depends: 无

## 背景

2026-09-01 容器环境修复时 (v1.0.0 后),发现 `cloud-skywalking-oap` 容器 Exited 0:

```
Error: crun: cannot stat `/tmp/sw-9.7.0-default.yml`: No such file or directory
       OCI runtime attempted to invoke a command that was not found
```

## 根因

skywalking-oap 9.7.0 镜像 entrypoint 期望 `/tmp/sw-9.7.0-default.yml` 配置。
但 docker-compose 挂载点:
```yaml
volumes:
  - ./skywalking/config/application.yml:/skywalking/config/application.yml:ro
```

**挂载的是 `/skywalking/config/application.yml`, 但 entrypoint 找 `/tmp/sw-9.7.0-default.yml`**。

entrypoint (apache/skywalking-oap-server:9.7.0 Dockerfile 内部) 启动命令可能是:
```sh
exec java ... OAPServerStartUp --config=/tmp/sw-9.7.0-default.yml
```

**配置路径不匹配** → OAP 无法加载 → 启动失败。

## 容器状态 (2026-09-01 20:11)

- ❌ `cloud-skywalking-oap`: Exited (0) (config 找不到)
- ❌ `cloud-skywalking-ui`: Exited (1) (依赖 oap, 启动失败)
- ✅ `cloud-prometheus`, `cloud-grafana`, `cloud-loki` 等其他 monitoring 容器: **正常运行**

## 修复方案

### 方案 A: 改 volume 挂载 (推荐)

1. 复制 `application.yml` 到 `/tmp/sw-9.7.0-default.yml` 路径
2. 或在容器内创建 symlink
3. 需要进容器内部改, 不优雅

### 方案 B: 改 compose volume 路径

```yaml
volumes:
  - ./skywalking/config/application.yml:/tmp/sw-9.7.0-default.yml:ro
```

但这要求 SW_OAP 默认读这个路径, 实际可能仍失败 (因为 application.yml 内部逻辑可能引用其他路径)。

### 方案 C: 升级到 9.7.0 正确版本

查 SkyWalking 9.7.0 文档, 找到正确的 application.yml 路径 (可能是 `/skywalking/config/application.yml`)。
`compose.yaml` 当前的挂载可能是对的, 但 entrypoint 期望的路径错了。
需要看 entrypoint 脚本。

### 方案 D: 不用 SkyWalking 9.7.0, 改用 10.x

10.x 镜像 (skywalking-oap-server:10.0.0) 路径可能不同, 但项目历史决策用 9.7.0。
如果有 10.x dockerfile, 可升级。

## 临时解决方案

SkyWalking 是 opt-in APM (默认不启用), 不阻塞核心业务。
v1.0.0 已发布, 告警和链路追踪不依赖 skywalking。
不阻塞用户使用。

## 不阻塞 v1.0.0

- v1.0.0 tag `e6b786d` 已发布
- prometheus/grafana/loki 替代方案可用
- skywalking 是"加分项" (APM 可观测性增强), 缺它不影响 P0-3b 或 M5-03 等核心功能

## 排查时间表

| 时间 | 事件 |
|---|---|
| 2026-08-31 19:00 | skywalking-oap 上次正常退出 (8-31 11:00 UTC 跑 compact) |
| 2026-09-01 03:49 | skywalking-oap 仍在清理 history data |
| 2026-09-01 09:00+ | 容器退出, 但 ctr.log 显示"正常"日志 (实际是被 conmon 异常杀) |
| 2026-09-01 20:00 | 启动时失败, `cannot stat /tmp/sw-9.7.0-default.yml` |

## 引用

- deploy/compose.yaml line 470-491: skywalking-oap 配置
- deploy/skywalking/config/application.yml: 配置内容
- docker hub: apache/skywalking-oap-server:9.7.0
- 详见 P3-01 容器清理 issue (容器环境问题总览)
