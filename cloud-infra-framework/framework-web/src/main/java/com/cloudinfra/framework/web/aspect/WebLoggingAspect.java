package com.cloudinfra.framework.web.aspect;

import com.cloudinfra.common.context.UserContext;
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
import java.util.Arrays;
import java.util.Set;

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

    // 敏感参数名集合（不区分大小写）
    private static final Set<String> SENSITIVE_PARAMS = Set.of(
        "password", "passwd", "pwd", "secret", "token", "apikey", "api_key",
        "access_token", "refresh_token", "authorization", "credential",
        "privatekey", "private_key", "apisecret", "api_secret"
    );

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
        String params = maskSensitiveParams(Arrays.deepToString(joinPoint.getArgs()));
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

    /**
     * 脱敏敏感参数
     */
    private String maskSensitiveParams(String params) {
        if (params == null) {
            return params;
        }
        String result = params;
        for (String sensitive : SENSITIVE_PARAMS) {
            // 匹配 key=value 或 "key": "value" 等模式
            result = result.replaceAll(
                "(?i)(" + sensitive + "\\s*[:=]\\s*\")([^\"]+)(\")",
                "$1***MASKED***$3"
            );
            result = result.replaceAll(
                "(?i)(" + sensitive + "\\s*[:=]\\s*)([^\\s,}]+)([\\s,}])",
                "$1***MASKED***$3"
            );
        }
        return result;
    }
}