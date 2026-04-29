package com.cloudinfra.framework.mybatis.aspect;

import com.cloudinfra.common.annotation.AuditLog;
import com.cloudinfra.common.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
@Slf4j
public class AuditLogAspect {

    @Before("@annotation(auditLog)")
    public void doBefore(JoinPoint joinPoint, AuditLog auditLog) {
        String userId = UserContext.get("userId");
        String username = UserContext.get("username");
        
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        
        log.info("审计日志 [模块: {}, 操作: {}] - 用户: {} (ID: {}), 方法: {}", 
                 auditLog.module(), auditLog.action(), username, userId, method.getName());
    }
}
