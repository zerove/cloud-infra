# P3-02: ADR-008 重复编号 (observability vs port-conventions)

Status: open
Priority: P3
Owner: TBD
Module: docs/adr/
Depends: 无

## 背景

`docs/adr/` 目录有 2 个文件都以 ADR-008 编号开头:

1. **`ADR-008-observability.md`** (2026-04-21, 2075 bytes)
   - 主题: Actuator + Micrometer + 链路追踪标准化
   - 范围: 仅 observability

2. **`ADR-008-port-conventions-and-virtual-threads.md`** (2026-08-04, 2789 bytes)
   - 主题: 服务端口约定 + 虚拟线程策略
   - 范围: 端口规范 + 虚拟线程 (内容更全, 显式引用 "ADR-008 核心")

## 影响

- **README.md badges**: 写 "ADRs=11" 但实际文件数 12 (含 2 个 ADR-008)
- **AGENTS.md**: 没具体数 ADR, 无影响
- **新读者困惑**: 看 `docs/adr/` 目录看到 2 个 ADR-008 不确定哪个权威

## 修复方案

**方案 A (推荐)**: 归档旧版
1. 把 `ADR-008-observability.md` 移到 `docs/adr/archive/ADR-008-observability-deprecated.md`
2. 添加废弃说明 (内容已被 ADR-008-port-conventions 涵盖)
3. README badges: ADRs=11 保持

**方案 B**: 重编号
1. 把 `ADR-008-observability.md` 重命名为 `ADR-012-observability.md`
2. 后续 ADR 编号顺延 (012 → 012, 013...)

**方案 C**: 合并
1. 把两个文件内容合并到 `ADR-008-port-conventions-and-virtual-threads.md`
2. 删除 observability 独立文件
3. 更新标题: `ADR-008: 服务端口约定 + 可观测性 + 虚拟线程策略`

## 决策点

- 推荐方案 A (最小改动, 保留历史)
- 真实历史价值: observability ADR 是 2026-04-21 早期决策, 不应丢
- 工作量: 0.5h

## 不阻塞 v1.0.0

v1.0.0 已发布, 此问题是 P3 卫生项。
新 HANDOFF-2026-09-01.md §7.2 已记录此问题。

## 引用

- ADR-008-observability.md (文件)
- ADR-008-port-conventions-and-virtual-threads.md (文件)
- HANDOFF-2026-09-01.md §7.2 (问题记录)
- README.md badges (ADRs=11 实际数 12)
