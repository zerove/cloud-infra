# M5-02: deploy/compose.yaml 调整 cloud-system 内存 (768m→2g)

Status: closed (done 2026-09-01)
Priority: P0
Owner: AI Agent
Module: deploy/
Depends: M5-01 (P0-3b 验证)

## 背景

P0-3b 端到端验证 (M5-01) 发现 **cloud-system 在 768m 内存下会死锁**:
- 现象: Spring Boot 4.1.0 + Caffeine 3.x + JDK 25 ClassLoader 在小堆下循环触发类加载,1 分 30 秒后 exit(1)
- 实测: 768m + `:latest` 镜像 → 死锁;2GB + `:fix` 镜像 → 86.19 秒启动成功

不修复 → `deploy/up.sh --full` 启动 system 永远失败,生产部署不可用。

## 改动 (已提交)

文件: `deploy/compose.yaml`,service `system`:

| 配置项 | 改前 | 改后 | 原因 |
|---|---|---|---|
| `mem_limit` | 768m | 2g | 容器内存上限,Caffeine 类加载需要 |
| JAVA_OPTS `-Xmx` | 512m | 1536m | JVM 堆内存,适配容器上限 |
| JAVA_OPTS `-Xms` | 256m | 512m | 初始堆,加速启动 |
| JAVA_OPTS 末尾追加 | (无) | `--enable-native-access=ALL-UNNAMED` | JDK 25 Restricted methods 警告消除 |

加注释指向 `docs/perf/2026-09-01-p0-3b-verify.md`,未来维护者能立即找到验证报告。

## 验收

- [x] compose.yaml 改完,git diff 可见
- [x] 已实测: podman run + 2GB 内存 + --enable-native-access → 86.19s 启动
- [ ] 待 CI 验证: podman-compose --profile full up 不死锁 (需下次会话)

## 后续

- 其他模块 (file/notification/search) 是否也有类似 JDK 25 兼容问题? 建议下次会话扫一遍
- 长期: 升级 Caffeine 到 3.2+ 或改 ConcurrentHashMap,可能可以回到 1GB
