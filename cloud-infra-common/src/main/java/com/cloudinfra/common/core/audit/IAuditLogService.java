package com.cloudinfra.common.core.audit;

/**
 * 审计日志服务接口，定义通用持久化标准
 */
@FunctionalInterface
public interface IAuditLogService {
    /**
     * 保存审计日志
     * @param logEntity 审计实体对象，使用 Object 传入以避免模块间依赖
     */
    void save(Object logEntity);
}
