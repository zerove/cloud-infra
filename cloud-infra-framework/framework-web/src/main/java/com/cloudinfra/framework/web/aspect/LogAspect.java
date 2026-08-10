package com.cloudinfra.framework.web.aspect;

import com.cloudinfra.common.core.annotation.Log;
import com.cloudinfra.common.core.domain.entity.SysOperLog;
import com.cloudinfra.common.core.event.LogEvent;
import java.time.LocalDateTime;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 操作日志记录处理
 */
@Aspect
@Component
public class LogAspect {

    private final ApplicationEventPublisher eventPublisher;

    public LogAspect(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 限定到 controller 层: 注解型切点若无类型过滤, Spring AOP 会对每个 bean 做 AspectJ 签名解析,
     * 在 JDK 25 反射下启动开销高达百秒级 (仅 system-module 即验证: main 线程卡在 World.resolveToReferenceType)
     */
    @org.aspectj.lang.annotation.Pointcut("execution(* com.cloudinfra..controller..*(..))")
    private void controllerPointcut() {
    }

    /**
     * 处理请求前执行
     */
    @Before(value = "controllerPointcut() && @annotation(controllerLog)")
    public void doBefore(JoinPoint joinPoint, Log controllerLog) {
        // 可以记录请求开始时间等
    }

    /**
     * 处理完请求后执行
     */
    @AfterReturning(
        pointcut = "controllerPointcut() && @annotation(controllerLog)",
        returning = "jsonResult"
    )
    public void doAfterReturning(
        JoinPoint joinPoint,
        Log controllerLog,
        Object jsonResult
    ) {
        handleLog(joinPoint, controllerLog, null, jsonResult);
    }

    /**
     * 拦截异常操作
     */
    @AfterThrowing(value = "controllerPointcut() && @annotation(controllerLog)", throwing = "e")
    public void doAfterThrowing(
        JoinPoint joinPoint,
        Log controllerLog,
        Exception e
    ) {
        handleLog(joinPoint, controllerLog, e, null);
    }

    protected void handleLog(
        final JoinPoint joinPoint,
        Log controllerLog,
        final Exception e,
        Object jsonResult
    ) {
        try {
            // 构造日志对象
            SysOperLog operLog = new SysOperLog();
            operLog.setStatus(0); // 正常
            operLog.setCreateTime(LocalDateTime.now());

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
