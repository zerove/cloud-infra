# P3-02: ADR-008 重复编号 (observability vs port-conventions)

Status: closed (done 2026-09-02)
Priority: P3
Owner: AI Agent
Module: docs/adr/
Depends: 无

## 背景

`docs/adr/` 目录有 2 个文件都以 ADR-008 编号开头:

1. **`ADR-008-observability.md`** (2026-04-21, 2075 bytes)
   - 主题: Actuator + Micrometer + 链路追踪标准化
2. **`ADR-008-port-conventions-and-virtual-threads.md`** (2026-08-04, 2789 bytes)
   - 主题: 服务端口约定 + 虚拟线程策略
   - 内容更新更全, 且引述可观测性

## 影响

- README.md badges: 写 "ADRs=12" 但实际有 1 个重复
- 新读者困惑: 看 `docs/adr/` 目录看到 2 个 ADR-008

## 修复 (2026-09-02 done)

### 动作

```bash
# 1. 归档旧 observability.md
mkdir -p docs/adr/archive
git mv docs/adr/ADR-008-observability.md docs/adr/archive/ADR-008-observability-deprecated.md

# 2. 旧 ADR 头部加 DEPRECATED 说明
# 引用新 ADR-008 + issue 12

# 3. README.md badges ADRs 12 → 11
```

### commits

- `4076713 fix(docs): P3-02 ADR-008 重复 - 归档旧 observability.md`
- `95259cf docs: ADRs=11 (README badge) + ADR-008-observability DEPRECATED 头部说明`

## 验证

- ✅ `docs/adr/` 现在有 11 个 ADR + 1 个 archive 目录 (12 个文件但 ADR 实际 11)
- ✅ README.md ADRs=11 一致
- ✅ 旧 ADR 头部有 DEPRECATED 警告 + 链接到新 ADR
- ✅ HEAD=95259cf, ahead origin = 0

## 不阻塞 v1.0.0

v1.0.0 tag `e6b786d` 已发布, 本修复在 v1.0.0 后, 算 v1.0.1 候选.

## 引用

- commits: 4076713 + 95259cf
- 新 ADR: `docs/adr/ADR-008-port-conventions-and-virtual-threads.md`
- 归档: `docs/adr/archive/ADR-008-observability-deprecated.md`
