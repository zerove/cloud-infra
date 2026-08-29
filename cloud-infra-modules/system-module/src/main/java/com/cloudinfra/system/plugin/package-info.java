/**
 * 平台扩展 (plugin 框架).
 *
 * <p>类型: 平台扩展 (混合)</p>
 * <p>职责: 业务插件运行时, 框架 + impl + hot-deploy watcher</p>
 * <p>耦合点: impl 子包直接 new SysTxMessage 等业务 model, 强业务耦合</p>
 * <p>抽离决策: ADR-009 — 暂缓抽离, 当前为业务下沉方向</p>
 */
package com.cloudinfra.system.plugin;
