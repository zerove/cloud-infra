# P3-14: skywalking 升级 11.0.0 (镜像 + application.yml 适配)

Status: open (3 个阻碍, 需未来 session 处理)
Priority: P3
Owner: TBD
Module: deploy/skywalking/
Depends: 拉 11.0.0 镜像 + application.yml 适配

## 背景 (2026-09-02/03 探查)

P3-14 尝试升级 `cloud-skywalking-oap` 镜像从 9.7.0 到 11.0.0, 与 application.yml 11.0.0 风格匹配。

## 探查 1: application.yml vs 9.7.0 镜像不匹配 (2026-09-02)

- 镜像 `apache/skywalking-oap-server:9.7.0` 启动失败 `ModuleNotFoundException: [receiver-runtime-rule, dsl-debugging, ui-management, inspect, admin-server, status] missing`
- 9.7.0 镜像不含这 6 个 admin modules (10.x+ 才有)

## 探查 2: 拉 11.0.0 镜像 (2026-09-02/03)

- **daocloud mirror 阻塞**: `unexpected EOF`, `503 Service Unavailable`
- **docker.io 直接也卡**: registry-1.docker.io 同样 503
- **网络问题**: 9-03 02:35 试拉多次都卡在 5-7/12 blob 进度
- **未成功拉到 11.0.0**

## 探查 3: 试 10.1.0/10.2.0 (2026-09-03 04:36)

| 镜像 | 存储 plugin 列表 | h2 storage | 结果 |
|---|---|---|---|
| 9.7.0 | es + banyandb + jdbc-hikaricp | ❌ | Exited "ModuleNotFound" |
| 10.1.0 | es + banyandb + jdbc-hikaricp | ❌ | Exited "no provider for storage" |
| 10.2.0 | es + banyandb + jdbc-hikaricp | ❌ | Exited "no provider for storage" |
| 11.0.0 | (未拉到,网络阻塞) | ? | ? |

**所有 10.x 镜像**都**没有 h2 storage plugin**!需要用 jdbc-hikaricp + H2 驱动配置, 但 `storage-jdbc-hikaricp-plugin` 在 10.x 镜像需要 `application.yml` 中 `jdbc-hikaricp` 块显式 provider 配置, 而**项目 application.yml 是 11.0.0 风格**, 不是 10.x 兼容。

## 真正修复路径 (3 个阻碍)

1. **拉 11.0.0 镜像** (网络稳定时): 绕过 daocloud, 用 `unqualified-search-registries` + `docker.io` 单独拉
2. **application.yml 适配 11.0.0**: 检查 `dsl-debugging` `ui-management` 等 modules 引入是否兼容
3. **完整 e2e 验证**: oap 启动 → admin-server HTTP 200 → 业务模块 trace 上报

## 当前状态 (2026-09-03 05:36)

- ✅ `git checkout HEAD -- deploy/skywalking/config/application.yml deploy/compose.yaml` 恢复原状
- ✅ 临时测试容器 (cloud-skywalking-oap/ui) 已删除
- ✅ 临时 log (/tmp/oap-*.log) 已清理
- ❌ **没有 commit 任何 P3-14 改动** (验证后回滚)
- ❌ **v1.0.0 tag e6b786d 未变**

## 不阻塞 v1.0.0

v1.0.0 tag `e6b786d` immutable, P3-14 改动会作为 v1.0.1 候选。  
Skywalking 是 opt-in APM (默认未启用), 不影响 P0-3b / M5-03 / codegen / 业务核心功能。

## 引用

- v1.0.0-rc1 探查: `.scratch/2026-q3-roadmap/issues/14-skywalking-config-path.md` (关闭)
- 历史升级: commit ccd0bac ("11.0.0 升级实测失败 → 回退 10.1.0 + H2")
- 多次网络阻塞: 2026-09-02 18:00, 2026-09-03 02:35, 2026-09-03 04:07
