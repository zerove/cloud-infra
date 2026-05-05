package com.cloudinfra.framework.web.aspect;

import com.cloudinfra.common.annotation.Log;
import com.cloudinfra.common.event.OperLogEvent;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
public class LogAspect {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @AfterReturning(pointcut = "@annotation(controllerLog)", returning = "jsonResult")
    public void doAfterReturning(JoinPoint joinPoint, Log controllerLog, Object jsonResult) {
        eventPublisher.publishEvent(new OperLogEvent(this, controllerLog.title(), 
                controllerLog.businessType(), jsonResult != null ? jsonResult.toString() : ""));
        log.info("审计日志事件已发布: {}", controllerLog.title());
    }
}
