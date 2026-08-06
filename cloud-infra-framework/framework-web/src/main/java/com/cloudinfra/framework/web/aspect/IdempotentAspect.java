package com.cloudinfra.framework.web.aspect;

import com.cloudinfra.common.core.annotation.Idempotent;
import com.cloudinfra.common.core.exception.GlobalException;
import com.cloudinfra.common.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class IdempotentAspect {

    private final RedisTemplate<String, Object> redisTemplate;

    public IdempotentAspect(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Before("@annotation(idempotent)")
    public void doBefore(JoinPoint joinPoint, Idempotent idempotent) {
        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) return;

        HttpServletRequest request = attributes.getRequest();

        // 1. 优先使用客户端显式提供的幂等令牌（X-Request-Token）
        String requestToken = request.getHeader("X-Request-Token");
        String key;
        if (requestToken != null && !requestToken.isBlank()) {
            key = "idempotent:" + request.getRequestURI() + ":" + requestToken;
        } else {
            // 2. 无令牌时回退：用户 + URI + 请求体摘要，
            //    避免无 token 请求共享同一 key 导致并发误杀
            Long userId = SecurityUtils.getUserId();
            String userPart = userId == null ? "anon" : String.valueOf(userId);
            String bodyDigest = digest(request);
            key = "idempotent:" + request.getRequestURI() + ":" + userPart + ":" + bodyDigest;
        }

        // 尝试设置 key，如果存在则表示请求重复
        Boolean success = redisTemplate
            .opsForValue()
            .setIfAbsent(
                key,
                "locked",
                Duration.ofSeconds(idempotent.expireSeconds())
            );

        if (Boolean.FALSE.equals(success)) {
            throw new GlobalException(idempotent.message());
        }
    }

    /**
     * 基于请求体/参数生成内容摘要（同一用户同一内容视为重复请求）
     */
    private String digest(HttpServletRequest request) {
        try {
            // 优先使用已读缓存（若前置过滤器已解析 body），回退 QueryString
            String body = (String) request.getAttribute("idempotent.body");
            if (body == null || body.isBlank()) {
                body = request.getQueryString();
            }
            if (body == null || body.isBlank()) {
                body = request.getContentType() == null ? "" : request.getContentType();
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
