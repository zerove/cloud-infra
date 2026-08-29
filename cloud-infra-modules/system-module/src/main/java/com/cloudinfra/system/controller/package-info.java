/**
 * 业务代码层 (controller 端点).
 *
 * <p>类型: 业务 (强业务)</p>
 * <p>职责: 接收 HTTP 请求, 委托 service, 返回 Result DTO</p>
 * <p>设计约束: 所有方法必须 @PreAuthorize + @AuditLog, 不放业务逻辑</p>
 *
 * @see com.cloudinfra.system.service 业务服务
 */
package com.cloudinfra.system.controller;
