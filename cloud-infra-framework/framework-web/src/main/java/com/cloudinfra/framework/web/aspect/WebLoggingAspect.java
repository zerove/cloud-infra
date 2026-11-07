package com.cloudinfra.framework.web.aspect;

import com.cloudinfra.common.context.UserContext;
import com.cloudinfra.common.util.SensitiveDataRedactor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;

/**
 * Web 请求日志切面
 * 自动记录所有 Controller 接口的请求参数、执行耗时及响应状态
 * 敏感参数（密码、token、密钥等）自动脱敏
 */
@Aspect
@Component
@RequiredArgsConstructor
public class WebLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(WebLoggingAspect.class);


    @Around("execution(* com.cloudinfra..controller..*(..))")
    public Object logWebRequest(ProceedingJoinPoint joinPoint) throws Throwable {
        Instant start = Instant.now();
        HttpServletRequest request = null;
        HttpServletResponse response = null;

        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            request = attributes.getRequest();
            response = attributes.getResponse();
        }

        String methodName = joinPoint.getSignature().toShortString();
        String params = SensitiveDataRedactor.redactArgs(joinPoint.getArgs());
        String userId = UserContext.get("userId");

        Object result;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            if (request != null && response != null) {
                log.error("Request failed | Method: {} | User: {} | Params: {} | Error: {}",
                    methodName, userId, params, e.getMessage());
            }
            throw e;
        } finally {
            long duration = Duration.between(start, Instant.now()).toMillis();
            if (request != null && response != null) {
                log.info("Request processed | Method: {} | User: {} | Status: {} | Duration: {}ms | Params: {}",
                    methodName, userId, response.getStatus(), duration, params);
            }
        }
    }
}