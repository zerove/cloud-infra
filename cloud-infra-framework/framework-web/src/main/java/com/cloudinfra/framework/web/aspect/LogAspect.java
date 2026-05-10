package com.cloudinfra.framework.web.aspect;

import com.cloudinfra.common.core.annotation.Log;
import com.cloudinfra.common.core.enums.BusinessType;
import com.cloudinfra.common.core.domain.entity.SysOperLog;
import com.cloudinfra.common.core.event.LogEvent;
import com.cloudinfra.common.util.ServletUtils;
import com.cloudinfra.common.util.StringUtils;
import com.cloudinfra.common.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class LogAspect {

    private final ApplicationEventPublisher publisher;

    @AfterReturning(pointcut = "@annotation(controllerLog)", returning = "jsonResult")
    public void doAfterReturning(JoinPoint joinPoint, Log controllerLog, Object jsonResult) {
        handleLog(joinPoint, controllerLog, null, jsonResult);
    }

    @AfterThrowing(value = "@annotation(controllerLog)", throwing = "e")
    public void doAfterThrowing(JoinPoint joinPoint, Log controllerLog, Exception e) {
        handleLog(joinPoint, controllerLog, e, null);
    }

    protected void handleLog(final JoinPoint joinPoint, Log controllerLog, final Exception e, Object jsonResult) {
        try {
            SysOperLog operLog = new SysOperLog();
            operLog.setStatus(0);
            
            String ip = ServletUtils.getClientIP();
            operLog.setOperIp(ip);
            operLog.setOperUrl(ServletUtils.getRequest().getRequestURI());
            
            String username = SecurityUtils.getUsername();
            operLog.setOperName(username);

            if (e != null) {
                operLog.setStatus(1);
                operLog.setErrorMsg(StringUtils.substring(e.getMessage(), 0, 2000));
            }
            
            String className = joinPoint.getTarget().getClass().getName();
            String methodName = joinPoint.getSignature().getName();
            operLog.setMethod(className + "." + methodName + "()");
            operLog.setRequestMethod(ServletUtils.getRequest().getMethod());
            
            getControllerMethodDescription(joinPoint, controllerLog, operLog, jsonResult);
            operLog.setOperTime(LocalDateTime.now());
            
            publisher.publishEvent(new LogEvent(this, operLog));
        } catch (Exception exp) {
            log.error("日志记录异常: {}", exp.getMessage());
        }
    }

    public void getControllerMethodDescription(JoinPoint joinPoint, Log log, SysOperLog operLog, Object jsonResult) {
        operLog.setBusinessType(log.businessType().ordinal());
        operLog.setTitle(log.title());
        operLog.setOperatorType(log.operatorType().ordinal());
    }
}
