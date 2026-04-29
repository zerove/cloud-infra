package com.cloudinfra.framework.mybatis.aspect;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cloudinfra.common.annotation.AuditLog;
import com.cloudinfra.common.context.UserContext;
import com.cloudinfra.common.core.audit.IAuditLogService;

import lombok.extern.slf4j.Slf4j;

@Aspect
@Component
@Slf4j
public class AuditLogAspect {

    @Autowired
    private IAuditLogService auditLogService;

    @Before("@annotation(auditLog)")
    public void doBefore(JoinPoint joinPoint, AuditLog auditLog) {
        String userId = UserContext.get("userId");
        String username = UserContext.get("username");

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        Map<String, Object> logInfo = new HashMap<>();
        logInfo.put("module", auditLog.module());
        logInfo.put("action", auditLog.action());
        logInfo.put("userId", userId);
        logInfo.put("username", username);
        logInfo.put("methodName", method.getName());
        logInfo.put("createTime", new Date());

        auditLogService.save(logInfo);
        log.info("Audit log saved: {}", logInfo);
    }
}
