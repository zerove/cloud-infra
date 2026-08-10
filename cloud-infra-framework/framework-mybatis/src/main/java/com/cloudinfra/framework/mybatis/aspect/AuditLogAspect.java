package com.cloudinfra.framework.mybatis.aspect;

import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.context.UserContext;
import com.cloudinfra.common.core.audit.IAuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 审计日志切面
 * 通过 @AuditLog 注解自动记录关键业务操作的执行轨迹
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);
    private final IAuditLogService auditLogService;

    public AuditLogAspect(IAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 限定到 controller/service 层: 避免注解型切点对全量 bean 做 AspectJ 签名解析拖慢启动
     */
    @org.aspectj.lang.annotation.Pointcut(
        "execution(* com.cloudinfra..controller..*(..)) || execution(* com.cloudinfra..service..*(..))"
    )
    private void businessPointcut() {
    }

    @Around("businessPointcut() && @annotation(auditLog)")
    public Object doAround(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        String userId = UserContext.get("userId");
        String username = UserContext.get("username");
        LocalDateTime startTime = LocalDateTime.now();
        
        // 记录请求参数
        Object[] args = joinPoint.getArgs();
        String params = args == null ? "[]" : Arrays.deepToString(args);

        Object result;
        String status = "SUCCESS";
        String errorMsg = null;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            status = "FAILED";
            errorMsg = e.getMessage();
            throw e;
        } finally {
            LocalDateTime endTime = LocalDateTime.now();
            long duration = java.time.Duration.between(startTime, endTime).toMillis();

            Map<String, Object> logInfo = new HashMap<>();
            fillLogInfo(logInfo, auditLog, userId, username, params, status, errorMsg, duration, endTime);
            
            auditLogService.save(logInfo);
            log.info("Audit log saved: module={}, action={}, status={}", auditLog.module(), auditLog.action(), status);
        }
    }

    private void fillLogInfo(Map<String, Object> logInfo, AuditLog auditLog, String userId, String username, String params, String status, String errorMsg, long duration, LocalDateTime endTime) {
        logInfo.put("module", auditLog.module());
        logInfo.put("action", auditLog.action());
        logInfo.put("userId", userId != null ? userId : "system");
        logInfo.put("username", username != null ? username : "system");
        sameParams(logInfo, params);
        logInfo.put("params", params);
        logInfo.put("status", status);
        logInfo.put("errorMessage", errorMsg);
        // 修正：duration 是 long，直接存入即可
        logInfo.put("duration", duration);
        logInfo.put("createTime", endTime);
    }

    private void sameParams(Map<String, Object> logInfo, String params) {
        // 预留参数清洗逻辑，例如脱敏
    }
}
