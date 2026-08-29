/**
 * 业务代码层 (service 用例).
 *
 * <p>类型: 业务 (强业务)</p>
 * <p>职责: 业务用例编排, 事务边界管理, 不放 SQL 拼接</p>
 * <p>设计约束: @Transactional 显式声明 rollbackFor, Feign 调用移出事务</p>
 *
 * @see com.cloudinfra.system.mapper 数据访问
 */
package com.cloudinfra.system.service;
