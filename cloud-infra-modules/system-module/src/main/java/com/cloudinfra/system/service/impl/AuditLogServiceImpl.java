package com.cloudinfra.system.service.impl;

import com.cloudinfra.common.core.audit.IAuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 默认审计日志服务实现，暂不持久化到数据库
 */
@Service
@Slf4j
public class AuditLogServiceImpl implements IAuditLogService {

    @Override
    public void save(Object logEntity) {
        log.info("Audit Log: {}", logEntity);
    }
}
