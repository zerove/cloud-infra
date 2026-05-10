package com.cloudinfra.framework.web.aspect;

import com.cloudinfra.common.core.annotation.Log;
import com.cloudinfra.common.core.domain.entity.SysOperLog;
import com.cloudinfra.common.core.event.LogEvent;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 操作日志记录处理
 */
@Aspect
@Component
@RequiredArgsConstructor
public class LogAspect {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 处理请求前执行
     */
    @Before(value = "@annotation(controllerLog)")
    public void doBefore(JoinPoint joinPoint, Log controllerLog) {
        // 可以记录请求开始时间等
    }

    /**
     * 处理完请求后执行
     */
    @AfterReturning(pointcut = "@annotation(controllerLog)", returning = "jsonResult")
    public void doAfterReturning(JoinPoint joinPoint, Log controllerLog, Object jsonResult) {
        handleLog(joinPoint, controllerLog, null, jsonResult);
    }

    /**
     * 拦截异常操作
     */
    @AfterThrowing(value = "@annotation(controllerLog)", throwing = "e")
    public void doAfterThrowing(JoinPoint joinPoint, Log controllerLog, Exception e) {
        handleLog(joinPoint, controllerLog, e, null);
    }

    protected void handleLog(final JoinPoint joinPoint, Log controllerLog, final Exception e, Object jsonResult) {
        try {
            // 构造日志对象
            SysOperLog operLog = new SysOperLog();
            operLog.setStatus(0); // 正常
            operLog.setOperTime(LocalDateTime.now());
            
            if (e != null) {
                operLog.setStatus(1); // 异常
                operLog.setErrorMsg(e.getMessage());
            }

            operLog.setTitle(controllerLog.title());
            operLog.setBusinessType(controllerLog.businessType().ordinal());
            
            // 发布事件，异步保存
            eventPublisher.publishEvent(new LogEvent(operLog));
        } catch (Exception exp) {
            // 记录日志异常
        }
    }
}
