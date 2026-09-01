# P3-03: .scratch 历史目录归档

Status: done (2026-09-01)
Priority: P3
Owner: AI Agent

## 背景

v1.0.0 发布后, .scratch/ 目录残留历史会话内容:

| 旧目录 | 状态 | 备注 |
|---|---|---|
| aotagent | 2026-08-26 创建, 旧 issue | 归档 |
| gateway-refresh-scope-misconfig | 2026-08-13 旧 issue | 归档 (closed 2026-08-25) |
| notification-permission-missing | 2026-08-28 旧 issue | 归档 (closed) |
| performance-baseline | 2026-08-28 旧 README | 归档 (superseded by docs/perf/) |
| project-analysis-2026-08-28.md | 2026-08-28 旧分析 | 归档 (superseded by 2026-09-01.md) |

## 动作 (2026-09-01)

全部移到 `.scratch/_archive-2026-q3-pre-roadmap/`,加 `_` 前缀避免与活跃目录混淆。

## 当前 .scratch 结构 (v1.0.0 后)

```
.scratch/
├── 2026-q3-roadmap/         # 本会话创建 (活跃)
│   ├── PRD.md
│   ├── SESSION-SUMMARY-2026-09-01.md
│   └── issues/               # 11 个 M5/P3 issues (9 closed + 2 open)
├── project-analysis-2026-09-01.md  # 本会话创建
└── _archive-2026-q3-pre-roadmap/    # 历史归档
    ├── aotagent/
    ├── gateway-refresh-scope-misconfig/
    ├── notification-permission-missing/
    ├── performance-baseline/
    └── project-analysis-2026-08-28.md
```

## 不阻塞 v1.0.0

v1.0.0 tag 稳定 `e6b786d`, 此动作纯卫生整理。
