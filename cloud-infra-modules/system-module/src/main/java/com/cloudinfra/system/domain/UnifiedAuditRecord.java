package com.cloudinfra.system.domain;

import java.time.LocalDateTime;

/**
 * 统一审计记录
 * 将系统操作日志和通知发送日志标准化
 */
public record UnifiedAuditRecord(
    LocalDateTime timestamp,
    String userId,
    String action,         // 行为描述 (例如: "上传文件", "发送邮件")
    String resourceId,     // 关联资源ID
    String status,         // SUCCESS, FAIL
    String details,        // 详细信息/错误原因
    String source          // 来源 (SYSTEM, NOTIFICATION)
) {}