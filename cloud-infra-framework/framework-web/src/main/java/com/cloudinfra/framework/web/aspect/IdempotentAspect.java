package com.cloudinfra.framework.web.aspect;

import com.cloudinfra.common.core.annotation.Idempotent;
import com.cloudinfra.common.core.exception.GlobalException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final RedisTemplate<String, Object> redisTemplate;

    @Before("@annotation(idempotent)")
    public void doBefore(JoinPoint joinPoint, Idempotent idempotent) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) return;
        
        HttpServletRequest request = attributes.getRequest();
        String key = "idempotent:" + request.getRequestURI() + ":" + request.getHeader("X-Request-Token");
        
        // 尝试设置 key，如果存在则表示请求重复
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, "locked", Duration.ofSeconds(idempotent.expireSeconds()));
        
        if (Boolean.FALSE.equals(success)) {
            throw new GlobalException(idempotent.message());
        }
    }
}
