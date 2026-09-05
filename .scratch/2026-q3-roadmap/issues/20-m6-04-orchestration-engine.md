# M6-04: 业务编排引擎基础 (基于 plugin 机制)

Status: open (survey done 2026-09-05, infra 已就位需架构选型)
Priority: P1
Owner: AI Agent (待架构师评审)
Module: cloud-infra-framework/framework-web + cloud-infra-common (核心) + 业务模块 (应用)
Depends: 无 (前置 M6-03 完成, plugin 机制 100% 就绪)

## 背景 (PRD M6-04)

> 业务编排引擎基础 (基于 plugin 机制). 平台增值能力.

## 现状盘点 (2026-09-05)

| 模块 | 文件 | 状态 | 备注 |
|---|---|---|---|
| framework-web | `plugin/IServicePlugin.java` | ✅ 已就位 | 接口: getMetadata/onEnable/onDisable |
| framework-web | `plugin/PluginMetadata.java` | ✅ 已就位 | record (id/name/version/description/properties) |
| framework-web | `plugin/PluginContext.java` | ✅ 已就位 | 服务注册 + 查找接口 |
| system-module | `plugin/PluginManager.java` | ✅ 已就位 | 插件生命周期管理 |
| system-module | `plugin/PluginRegistry.java` | ✅ 已就位 | 插件注册表 |
| system-module | `plugin/PluginContextImpl.java` | ✅ 已就位 | 上下文实现 |
| system-module | `plugin/HotDeploymentWatcher.java` | ✅ 已就位 | 热部署 (dev-only, ADR-010) |
| system-module | `plugin/impl/KnowledgeAssistantPlugin.java` | ✅ 示例 | AI 助手插件 |
| system-module | `plugin/impl/MediaAiTaggingPlugin.java` | ✅ 示例 | 媒体标签插件 |
| **业务编排引擎** | 不存在 | ❌ **缺失** | PRD 验收标准 |
| **Workflow 引擎** | 不存在 | ❌ **缺失** | saga/state machine 协调 |

## 关键澄清: Plugin vs Orchestration

**Plugin** (已有): 能力注册机制. "我能做什么".
- 加载/卸载生命周期
- 服务注册到 PluginContext
- 业务模块消费插件能力

**Orchestration** (待建): 流程调度机制. "我该按什么顺序做什么".
- Workflow/State Machine/Saga
- 步骤依赖 + 重试 + 补偿
- 跨服务/跨插件协调

两者互补, 不冲突. M6-04 核心是**业务编排**, 不是扩展 plugin.

## 验收标准 (PRD)

> M6-04: 业务编排引擎基础 (基于 plugin 机制)

需补 3 项核心能力:
1. **流程定义 DSL**: JSON/YAML 描述业务流程步骤
2. **执行引擎**: 步骤依赖 + 重试 + 超时 + 补偿
3. **plugin 集成点**: 编排步骤调用插件 (复用现有 IServicePlugin)

## 架构选型 (待架构师评审)

### 选项 A: 自研轻量编排

- 优点: 与现有 Plugin 机制无缝集成, 无外部依赖
- 缺点: 长期演进需补全 feature (parallel/foreach/compensation)
- 工作量: 5d (估计 PRD 估时)

### 选项 B: 集成 Camunda 7/8

- 优点: BPMN 2.0 标准, UI 完善 (Cockpit/Tasklist)
- 缺点: 与 Plugin 机制集成需要适配层, 学习曲线陡
- 工作量: 7-10d (适配层 + 概念对齐)

### 选项 C: 集成 Temporal

- 优点: code-first workflow, 强大的 saga 补偿
- 缺点: 需要 Temporal Server (额外基础设施)
- 工作量: 5-7d (Server 部署 + SDK 集成)

**建议**: 选项 A (自研轻量). 平台当前规模不需要完整 BPMN/Temporal. 自研可控制复杂度, 与 Plugin 机制天然集成.

## 实施阶段 (估时 5d, 估选项 A)

### Phase A: 流程定义 + 引擎核心 (2d)

1. **流程定义 JSON Schema**:
   - 步骤 (step): type/id/handler/dependsOn/retry/timeout/onFailure
   - 流程 (workflow): name/version/steps/triggers
2. **`WorkflowEngine`** (framework-web):
   - 解析 JSON 步骤
   - DAG 拓扑排序 (执行顺序)
   - 步骤执行 + 重试 + 超时
   - 失败传播 (依赖步骤全部跳过)
3. **`WorkflowContext`**: 当前步骤状态 + 共享数据 (workflow variables)

### Phase B: Plugin 集成 + 补偿 (1.5d)

1. **`PluginStepHandler`**: 抽象类, 业务插件继承后即可作为编排步骤
2. **Saga 模式**: 每个步骤可指定 onFailure handler (compensating action)
3. **步骤结果传递**: output → workflow variable, 后续步骤可引用 (类似 Argo DAG args)

### Phase C: 单元测试 + 示例 (1.5d)

1. **`WorkflowEngineTest`**: 8 测试
   - 单步骤执行
   - 多步骤 DAG 顺序
   - 重试 + 超时
   - 失败传播
   - Saga 补偿
   - Plugin 步骤集成
   - 循环引用检测
   - workflow variable 传递
2. **示例**: 订单处理 workflow (订单创建 → 支付 → 库存扣减 → 发货)
3. **文档同步**: AGENTS.md + ARCHITECTURE.md + ADR (新增 ADR-012 编排引擎)

## 关键设计

### 流程定义 JSON 示例

```json
{
  "name": "order-process",
  "version": "1.0",
  "steps": [
    {
      "type": "plugin",
      "id": "create-order",
      "handler": "OrderPlugin.create",
      "args": { "userId": "${trigger.userId}" }
    },
    {
      "type": "plugin",
      "id": "charge-payment",
      "handler": "PaymentPlugin.charge",
      "dependsOn": ["create-order"],
      "args": { "orderId": "${steps.create-order.output.orderId}" },
      "retry": { "max": 3, "interval": "1s" },
      "onFailure": "refund-order"
    },
    {
      "type": "plugin",
      "id": "deduct-inventory",
      "handler": "InventoryPlugin.deduct",
      "dependsOn": ["create-order"],
      "args": { "items": "${trigger.items}" }
    },
    {
      "type": "plugin",
      "id": "ship-order",
      "handler": "ShippingPlugin.ship",
      "dependsOn": ["charge-payment", "deduct-inventory"]
    }
  ]
}
```

### 与现有 Plugin 机制的集成

```
Workflow 步骤定义:
  type=plugin, handler="XxxPlugin.method"
        ↓
WorkflowEngine.execute()
        ↓
PluginRegistry.get(pluginId)
        ↓
PluginContext.getService(pluginClass)
        ↓
业务插件方法调用
```

## 进度

- [x] **2026-09-05 01:00**: kickoff + infra survey
- [x] **2026-09-05 01:11**: Phase A 完成 (WorkflowEngine + WorkflowStep + WorkflowContext + WorkflowResult + 8 单元测试)
- [ ] Phase B: PluginStepHandler 抽象 + Saga 补偿 (待架构师评审)
- [ ] Phase C: 单元测试 + 示例 workflow + ADR-012

## 不在本 issue 范围

- Camunda/Temporal 完整集成 (选项 B/C) → 留战略储备
- 工作流 UI (BPMN Modeler/Cockpit) → M7+
- 跨服务 Saga (vs 单进程) → M7+ (需 CDC + Outbox 已就位)

## 关联

- PRD M6-04 (`.scratch/2026-q3-roadmap/PRD.md`)
- 现有 Plugin 机制 (cloud-infra-framework/framework-web/plugin/*)
- ADR-010 插件安全模型 (dev-only 热部署)
- M6-01/02/03 已完成 (commit 17ee31a/09340da)