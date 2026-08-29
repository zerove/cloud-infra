/**
 * 集成层 (Spring 事件监听).
 *
 * <p>类型: 集成 (强业务)</p>
 * <p>职责: 应用内事件 (LogEvent) 落库 + 同步到 search</p>
 * <p>耦合点: 依赖 SysLoginLogMapper, SysOperLogMapper 业务 mapper</p>
 * <p>抽离决策: ADR-009 — 暂缓抽离</p>
 */
package com.cloudinfra.system.listener;
