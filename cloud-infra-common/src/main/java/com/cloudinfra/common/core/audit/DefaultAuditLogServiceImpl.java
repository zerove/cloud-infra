package com.cloudinfra.common.core.audit;

import lombok.extern.slf4j.Slf4j;

/**
 * 默认审计日志服务，当没有其他实现时由框架自动装配生效。
 * 注意: 不要在此类上使用 @Service/@ConditionalOnMissingBean,
 * 组件扫描阶段的 @ConditionalOnMissingBean 评估会把候选自身算入已有 bean,
 * 导致条件永远不匹配。默认实现的装配由框架自动配置完成。
 */
@Slf4j
public class DefaultAuditLogServiceImpl implements IAuditLogService {

    @Override
    public void save(Object logEntity) {
        log.info("Default Audit Log: {}", logEntity);
    }
}
