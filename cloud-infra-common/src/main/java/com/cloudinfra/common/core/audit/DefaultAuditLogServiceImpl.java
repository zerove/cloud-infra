package com.cloudinfra.common.core.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

/**
 * 默认审计日志服务，当没有其他实现时自动生效。
 */
@Service
@Slf4j
@ConditionalOnMissingBean(IAuditLogService.class)
public class DefaultAuditLogServiceImpl implements IAuditLogService {

    @Override
    public void save(Object logEntity) {
        log.info("Default Audit Log: {}", logEntity);
    }
}
