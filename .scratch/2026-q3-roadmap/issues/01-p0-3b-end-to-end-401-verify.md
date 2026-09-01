# M5-01: P0-3b 端到端 401 行为验证

Status: closed (verified 2026-09-01)
Priority: P0
Owner: AI Agent
Module: cloud-infra-framework/framework-security + cloud-infra-modules/system-module
Depends: 无

## 结论

✅ **P0-3b 端到端验证通过 (2026-09-01)**。

详细报告: `docs/perf/2026-09-01-p0-3b-verify.md`

## 关键证据

- 步骤 A: 登录后访问 /system/user/info → HTTP 200 ✅
- 步骤 B: 登出 → HTTP 200 + 写 Redis blacklist 键 ✅
- 步骤 C (P0-3b 关键): 用同一 token 直连 system:8082 → **HTTP 401** ✅
- 响应头含 `X-Blacklist-Jti: 1ed66d11-...` 证明 blacklist 机制生效
- Redis TTL 86387 秒 (≈24h, 设计正确)

## 副产物: Caffeine ClassLoader 死锁解决方案

| 配置 | 启动结果 |
|---|---|
| 768m 内存 + :latest 镜像 | 1 分 30 秒后 exit(1) — 死锁 |
| 2GB 内存 + :fix 镜像 | 86.19 秒启动成功 |
| 4GB 内存 + :fix 镜像 | 1 分钟左右启动成功 |

**建议**: 把 cloud-system `mem_limit` 从 768m 调到 2g,或加 `--enable-native-access=ALL-UNNAMED`。

## 后续建议

- 调整 deploy/compose.yaml 的 cloud-system 内存限制 (待 M5-02 一起改)
- 跑完整 e2e-test.sh (网关路径 401 也需测,本次只测直连)
- 长期方案: 升级 Caffeine / 改 ConcurrentHashMap / 降 Spring Boot (M7+)

## 修复链背景 (历史)

P0-3 (commit 225b101) 和 P0-3b (commit 370dcd1 + ae06566) 修复了 JWT blacklist 检查的三处遗漏:
1. JwtAuthenticationFilter 直连模式 (225b101)
2. HeaderAuthenticationFilter 直连模式 (370dcd1)
3. 缓存逻辑 + ArchitectureTest reactor 兼容 (ae06566)

字节码层已确认修复 (image 内含 `writeBlacklistUnauthorized` / `extractJtiFromBearer`),但**端到端 401 行为未实测**,直至 2026-09-01 本会话验证通过。
