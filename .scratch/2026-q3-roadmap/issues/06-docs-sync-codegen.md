# M5-06: CONTEXT.md / ROADMAP 同步 codegen 模块

Status: open
Priority: P1
Owner: TBD
Module: docs/
Depends: M5-04

## 背景

2026-08-31 8 个 commit 引入 `cloud-infra-codegen` 模块,但**核心文档未同步**:
- CONTEXT.md (项目上下文): 未提 codegen
- ROADMAP.md (路线图): 37/37 标记完成,但 codegen 不在其中
- ARCHITECTURE.md: 未提 codegen 在架构中的位置
- README.md: 快速开始未提 codegen

AGENTS.md 已在本会话 (55cd647) 同步,但其他文档未动。

## 任务

### 1. CONTEXT.md
- 在 "模块" 段落加 cloud-infra-codegen 一行
- 在 "代码生成" 段落加 (或新建) 说明 codegen 用途

### 2. ROADMAP.md
- 在 Milestone 4 (安全与卫生) 后,加 **Milestone 5: 代码生成框架** (🟡 In Progress)
- 列出 codegen 7 个子任务 (模板/测试/CI/Parser 等)

### 3. ARCHITECTURE.md
- 在模块拓扑图加 codegen
- 在"开发者指南"段落加 codegen 链接

### 4. README.md
- 快速开始加 codegen 一行 (Maven 插件调用示例)
- 文档索引加 codegen README 链接

## 验收

- [ ] 4 个文档均更新
- [ ] 没有 stale 引用 (codegen 文档互相一致)
- [ ] AGENTS.md / CONTEXT.md / ROADMAP.md 数字基线一致

## 参考

- AGENTS.md 同步: commit 55cd647
- codegen README: `cloud-infra-codegen/README.md`
- ROADMAP 模板: 见 Milestone 1-4 现有格式
