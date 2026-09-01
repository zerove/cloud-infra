# P3-01: 基础设施容器清理 (会话结束未停)

Status: open
Priority: P3
Owner: 用户决定
Module: 容器
Depends: 无

## 背景

本会话 2026-09-01 20:33 ~ 2026-09-02 02:13 持续运行基础设施容器 5.5 小时:
- cloud-postgres (PostgreSQL 16, 含 P0-3b 验证用 admin user + Pwd 验证数据)
- cloud-redis (Redis 7.2, 含 P0-3b blacklist 键)
- cloud-rabbitmq
- cloud-elasticsearch
- cloud-nacos (3.x)
- cloud-minio
- cloud-sentinel

**未在会话结束时主动 stop**.

## 资源占用 (2026-09-02 02:16 实测)

- 内存: 19Gi 总, 4.2Gi 已用, **15Gi 可用** (充裕)
- 磁盘: 720G 可用 (充裕)
- 进程: java + postgres + elasticsearch + conmon (nacos) 都在跑

## 影响评估

**风险低**:
- 资源不紧张
- 不影响其他用户
- 容器自愈 (restart policy)

**风险中**:
- 持续占用 1-2GB 内存 (可忽略)
- 长期不清理可能导致磁盘日志满
- 端口占用 (5432/6379/8848 等) 影响下次启动

## 选项

| 选项 | 命令 | 适用 |
|---|---|---|
| A. 完全 stop | `podman stop cloud-postgres cloud-redis cloud-rabbitmq cloud-elasticsearch cloud-nacos cloud-minio cloud-sentinel` | 长时间离开 (几天) |
| B. 完全 stop + 清理 | `podman-compose -f deploy/compose.yaml down` | 重新开始 (drop 数据库) |
| C. 保留 | 不动 | 继续 dev session |

## 建议

短期 (几天内继续 dev): 选 C 保留
中期 (下周再开会话): 选 A stop 释放端口
长期 (重置环境): 选 B

## 不阻塞

v1.0.0 已发布, 项目状态: ahead origin/master = 0, tag v1.0.0 已 force push.
容器清理是卫生项, 不影响任何 v1.0.0 验收标准.
