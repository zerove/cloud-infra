package com.cloudinfra.framework.web.aspect;

import com.cloudinfra.common.annotation.Idempotent;
import com.cloudinfra.framework.redis.service.RedisService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

@Aspect
@Component
public class IdempotentAspect {

    @Resource
    private RedisService redisService;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String key = "idempotent:" + request.getRequestURI() + ":" + request.getRemoteAddr() + ":" + idempotent.key();
        
        Object existing = redisService.get(key);
        if (existing != null) {
            throw new RuntimeException("请勿重复提交");
        }
        
        redisService.set(key, "1", idempotent.expireSeconds() * 1000L);
        
        return joinPoint.proceed();
    }
}
