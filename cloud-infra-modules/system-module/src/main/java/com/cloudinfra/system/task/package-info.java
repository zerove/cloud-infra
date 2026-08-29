/**
 * 集成层 (定时任务).
 *
 * <p>类型: 集成 (强业务)</p>
 * <p>职责: 事务消息重试定时调度</p>
 * <p>耦合点: 依赖 SysTxMessageService, 强业务耦合</p>
 * <p>抽离决策: ADR-009 — 暂缓抽离</p>
 */
package com.cloudinfra.system.task;
