# P3-04: skywalking-oap config 路径问题 (升级 10.x 才能解决)

Status: open (无法在 9.7.0 镜像下修复, 需升级到 10.x)
Priority: P3
Owner: TBD
Module: deploy/skywalking/
Depends: 无

## 背景 (2026-09-02 升级后探查)

初始报告 (`P3-04 skywalking config 路径`) 假设问题是 volume 挂载路径错 (`/skywalking/config/application.yml` vs entrypoint 找的 `/tmp/sw-9.7.0-default.yml`)。

**本会话(9-02) 实际探查后发现真正根因**:

### 镜像 + application.yml 不匹配

| 项 | 实际 |
|---|---|
| 镜像 | `apache/skywalking-oap-server:9.7.0` |
| application.yml 风格 | 10.x (含 `receiver-runtime-rule`, `dsl-debugging`, `status`, `inspect`, `ui-management` 5 个 modules) |
| 9.7.0 镜像实际 modules | `skywalking-jvm-receiver-plugin`, `skywalking-trace-receiver-plugin` 等基础 receivers. **不含 10.x 的 5 个 admin modules** |
| 错误 | `ModuleNotFoundException: [receiver-runtime-rule, dsl-debugging, ui-management, inspect, admin-server, status] missing` |

### 我尝试的修复 (全部失败)

1. **降 application.yml 适配 9.7.0**: 注释 5 个 modules → 但又出现 `no provider found for module storage` (selector=h2, 但 9.7.0 镜像无 h2 storage plugin, 只有 h2 数据库 driver)
2. **拉 10.1.0 / 10.2.0 镜像**: 拉取成功, 但 application.yml 引用的 modules 仍不存在 (5 admin modules 在 10.2.0 也没有, 11.0.0 才有)
3. **拉 11.0.0 镜像**: 受 daocloud mirror 网络限制, blob 下载不完整, 无法实测

### 真正根因

`deploy/skywalking/config/application.yml` 是 **SkyWalking 11.0.0 时代的配置**,但 compose 用 9.7.0 镜像 (commit 2242ce9 "ES 9.0.2 → 8.18.0 + SW OAP 9.6.0 → 10.1.0 升级" 部分升级, 实际只升到 9.7.0)。

## 真正修复方向

**选项 A: 升级镜像到 11.0.0** (匹配 application.yml)
- 改 `deploy/compose.yaml`: `image: apache/skywalking-oap-server:11.0.0`
- 验证: 11.0.0 应含所有 5 admin modules
- 工作量: 1 行修改 + 完整 e2e 验证

**选项 B: 降 application.yml 到 9.7.0 兼容**
- 删除 5 admin modules sections
- 改 storage selector 适配
- 9.7.0 镜像无 h2 storage plugin, 需 jdbc-hikaricp + 手动 schema init
- 工作量: 大 (可能 200+ 行改动)

**选项 C: 用 10.2.0 镜像 + 重新写 application.yml**
- 10.2.0 介于 9.7.0 和 11.0.0 之间
- 需先确认 10.2.0 实际含哪些 modules
- 工作量: 中

**推荐选项 A** (最小改动, 镜像 + 配置匹配)。

## 实际 revert 状态

本会话所有 P3-04 改动已 revert (`git checkout HEAD --`),**保持原状**。问题留作"未来 session 升级 10.x 或 11.0.0"工作。

## 不阻塞 v1.0.0

- v1.0.0 tag `e6b786d` 已发布
- 告警/链路追踪不依赖 skywalking
- skywalking 是"加分项" (opt-in APM)

## 建议未来 session

1. 选择 11.0.0 镜像 (最直接, application.yml 已是 11.0.0 风格)
2. 改 `deploy/compose.yaml` `image: apache/skywalking-oap-server:9.7.0` → `:11.0.0`
3. 拉镜像 (daocloud mirror 网络问题, 改用 docker.io 官方)
4. 重启 skywalking-oap, 验证 admin modules 启动成功
5. 同时更新 `skywalking-ui` 镜像 (用 11.0.0 配套版本)

## 拉镜像 workaround (本会话已用)

编辑 `~/.config/containers/containers.conf` 临时禁用 daocloud mirror:

```toml
unqualified-search-registries = ["docker.io"]
[[registry]]
prefix = "docker.io"
location = "docker.io"
# [[registry.mirror]] commented out
```

**注**: 11.0.0 拉取中 7/12 blob 时卡住, 可能是网络问题. 未来拉镜像需有更长 timeout 或在网络好的时间拉.

## 当前 container 状态

- `cloud-skywalking-oap`: 已清理 (rm -f, Exited 状态不再残留)
- `cloud-skywalking-ui`: Exited (1) 17 hours ago — 已知问题, UI 镜像 10.2.0 实际无问题 (ui 镜像 10.2.0 在, 是 oap 镜像问题)

## 引用

- 实际试错过程: 本会话 (2026-09-02) 12:00-12:53
- 镜像版本: `apache/skywalking-oap-server:9.7.0` (deploy/compose.yaml)
- application.yml: `deploy/skywalking/config/application.yml` (v11.0.0 风格)
- 历史 commit: 2242ce9 (SW 9.6.0 → 10.1.0 升级) — 部分完成, application.yml 没同步
- 相关 issue 14 (本会话 P3-04 首次报告)
