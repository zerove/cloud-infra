# ADR-009: 暂缓 platform-extensions 抽离 (system-module 高度耦合)

## 状态
2026-08-29 提议 / 待评审

## 背景
`cloud-infra-modules/system-module` 当前 LOC 3584, 包含业务 (controller/service/domain/mapper)
与平台扩展 (plugin 框架 / 事务消息 / 审计监听) 混合。前期分析报告 (2026-08-28) 提议 A4
抽离 platform-extensions 模块, 目标 5596 → 3000 LOC。

## 决策
**暂缓抽离**, 改为在系统模块内做职责分层 (package-info 标注), 后续以"防腐层"
方向逐步解耦, 而非直接物理切分。

## 现状分析

| 子包 | 文件数 | LOC | 类型 | 抽离可行性 |
|---|---|---|---|---|
| controller | 15 | 1029 | 业务 | ❌ 强业务 |
| service | 19 | 655 | 业务 | ❌ 强业务 |
| domain | 20 | 642 | 业务 | ❌ 强业务 |
| converter | 4 | 356 | 业务 | ❌ 强业务 |
| mapper | 20 | 193 | 业务 | ❌ 强业务 |
| config | 5 | 197 | 横切 | 🟡 部分 |
| plugin | 4 + impl | 279 | 混合 | 🟡 框架可抽, impl 不可 |
| mq | 2 | 110 | 业务 (基于 SysTxMessage) | ❌ 强业务 |
| task | 1 | 61 | 业务 (基于 SysTxMessage) | ❌ 强业务 |
| listener | 1 | 34 | 业务 (基于 mapper) | ❌ 业务耦合 |
| dto/exception | 2 | 28 | 业务 | ❌ 业务 |

**完全自包含的"可零成本抽离"代码**: 0 LOC (0%)

**结论**: system-module 当前**没有任何子包是真正"平台扩展"性质**——plugin 框架虽像平台,
但 plugin impl 直接调用业务 domain; mq/task/listener 全是 SysTxMessage + Sys*Mapper
的强业务实现。

## 抽离失败的根因 (3 个外部引用)

1. `com.cloudinfra.system.plugin.impl.KnowledgeAssistantPlugin` 直接 new `SysTxMessage`
   并 `sysTxMessageService.save()` — plugin 实现 = 业务用例
2. `com.cloudinfra.system.mq.TxMessageConsumer` 通过 `SysTxMessageService` 处理事务消息 —
   MQ 消费 = 业务事件 handler
3. `com.cloudinfra.system.task.TxMessageRetryTask` 同上, 定时任务 = 业务调度

## 替代方案 (替代物理抽离)

### 方案 1: package-info 标注职责
不改代码, 在每个子包加 `package-info.java` 标注:
- `@Business` 业务代码 (controller/service/domain/mapper/converter/dto/exception)
- `@PlatformExtension` 平台扩展 (plugin 框架, 但不抽离)
- `@Integration` 集成层 (mq/task/listener, 但不抽离)

**收益**: 工程导览清晰, 不破坏依赖
**成本**: 几乎零

### 方案 2: plugin 框架抽到 framework 单独子模块
仅抽 `PluginContext/PluginManager/PluginRegistry/HotDeploymentWatcher` 框架 4 个文件 (~120 LOC)
到 `cloud-infra-framework/framework-plugin`。
- 但 `PluginRegistry` 当前用 `SysPluginConfig` 业务 model, 需抽象 plugin config 为通用接口
- 工作量: 0.5d 框架 + 1d 业务 model 抽象 + 1d 测试

**收益**: plugin 框架 100% 复用
**成本**: 2.5d, 需重写 plugin config

### 方案 3: 接受现状, 用 ADR 记录
写本 ADR 记录耦合 + 决策, 留待 system-module 业务大改时一并重构

**收益**: 不投入即时期
**成本**: 0

## 决定
**采用方案 1 + 方案 3**: 写本 ADR 记录决策 + 立即加 package-info 标注, 待有 plugin
业务变更需求时再做方案 2 框架抽离。

## 影响
- system-module LOC 维持 3584 不变
- 工程导览改善 (package-info)
- 后续 plugin 业务迭代时, 决策点明确 (走 framework-plugin 抽离 vs 业务下沉)
