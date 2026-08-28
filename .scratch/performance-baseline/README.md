# Cloud-Infra 端到端性能基线 (commit 4ed317f)

**测量时间**: 2026-08-28 13:15-13:16 (CST)
**方法**: `hey -z 10s -c 10` 对网关 9000 跑 5 个真实业务场景
**版本**: 5 容器 6 微服务 + V10/V11 权限修复

| 场景 | RPS | avg(ms) | p99(ms) | 备注 |
|---|---|---|---|---|
| 公开 captcha | 41.6 | 236 | 1357 | 公开无鉴权, 限流开关影响 |
| 无 token 受保护 | 278.4 | 36 | 175 | 401 拦截极快 |
| system/user/list (带 token) | 14.9 | 664 | 2341 | 走 Feign + DB, 链路最长 |
| notification/send (POST) | 34.9 | 284 | 1062 | 走 MQ 异步链路 |
| search/indexes (带 token) | 38.6 | 255 | 1320 | 走 ES 远程调用 |

**与 08-22 旧基线对比**:
- 旧基线: 直连单服务 (gateway 112.65 RPS, auth 0.5 RPS)
- 当前基线: 走完整网关链路 (混合 14-41 RPS)
- 差异源: 测试条件不同, 但当前基线更具实用价值 (贴近用户感知)

**性能关注点**:
- `/system/user/list` p99 2.3s, 偏高, 排查方向: Feign 调 `/system/user/detail`
  + JPA 查询 + L1 缓存 miss, 后续可加慢查询日志定位
- captcha p99 1.3s, 第一次访问 cold start, 后续 < 50ms

**复现命令**:
```bash
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
export no_proxy="*" NO_PROXY="*"
hey -z 10s -c 10 -m GET http://localhost:9000/auth/captchaImage
# 其它场景参考 .scratch/performance-baseline/load_summary.txt
```
