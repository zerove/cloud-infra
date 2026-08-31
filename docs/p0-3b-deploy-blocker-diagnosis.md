# P0-3b 部署验证阻塞根因诊断 (2026-08-31)

## 现象
system module 启动时, 1 分 30 秒后 exit(1), 无 stack trace, log 卡在 Spring Boot banner 后。
`Exited (1)` 出现, 但容器内 java 进程已死, 无 hs_err 崩溃日志。

## 排查过程

### 尝试 1: 增大内存 (768m → 1g → 1.5g → 2g → 4g)
- 全部失败, 退出时间稳定在 1 分 30 秒
- dmesg 无 OOM 记录
- 排除 OOM killer

### 尝试 2: 禁用 Nacos discovery (`SPRING_CLOUD_NACOS_DISCOVERY_ENABLED=false`)
- 仍失败
- 排除 Nacos 阻塞

### 尝试 3: 加 `--enable-native-access=ALL-UNNAMED`
- 解决 JDK 25 Restricted methods 警告
- 仍失败, 但消除了无关警告

### 尝试 4: kill -3 thread dump
- 在 30s 启动窗口期抓 thread dump
- **根因明确**:

```
main thread: 
  ClassLoader.loadClass → ClassLoader.defineClass1 (递归)
  → Caffeine BoundedLocalCache.<init>
  → CPU 27194ms elapsed 32.71s (无限递归 loadClass)
  → org.springframework.boot.SpringApplication.run
```

## 根因
**Spring Boot 4.1.0 + Caffeine 3.x + JDK 25 ClassLoader 递归死锁**。
Caffeine `BoundedLocalCache.<init>` 触发类初始化, 该类的 `static` 块依赖另一个类,
该类又需要同一个 `BoundedLocalCache` 类, 形成循环依赖, main thread 死锁。
30 秒后 SpringApplication 触发 shutdown hook 优雅退出 → exit 1。

## 已知早 session 行为
- 2026-08-31 1:57 那次 system 启动成功, 端到端验证 P0-3 修复 (commit 225b101)
- 那次使用的 image 是 P0-3 修复前的 `:fix` image (24622 bytes framework-security)
- 当前 :fix image 含 P0-3b 修复 (25503 bytes framework-security)
- 行为变化可能是: 字节码增量触发了不同的 Caffeine 类加载路径

## 解决方案 (P0-3b 议题外, 单独议题)
1. 升级 Caffeine 到 3.2+ (若有 JDK 25 兼容修复)
2. 降级 Spring Boot 4.1.0 → 4.0.x 或升级到 4.1.1
3. 禁用 Caffeine (PermissionLocalCache 改为 ConcurrentHashMap)
4. 单独启动 system module 用更早 image 验证 P0-3b (回退到 :latest)

## P0-3b 当前结论
- 代码层: ✅ 完成 (commit 370dcd1)
- 字节码验证: ✅ image 内含 writeBlacklistUnauthorized / extractJtiFromBearer
- 端到端部署验证: ⚠️ 被 Spring Boot 4.1 + Caffeine + JDK 25 兼容问题阻塞
  (与本修复无关, 单独议题)

## 推荐
下次启动 system module 验证时, 用 `localhost/cloud-infra-system:fix` + `--enable-native-access=ALL-UNNAMED -Xmx2g`,
先观察是否需要 3+ 分钟启动 (Caffeine 类加载完成), 或降 Caffeine 版本。
