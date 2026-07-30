package com.cloudinfra.gateway.infrastructure.auth;

import com.cloudinfra.gateway.domain.auth.SessionStore;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class RedisSessionStore implements SessionStore {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisSessionStore(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("blacklist:" + jti));
    }

    @Override
    public boolean validateSession(Long userId, String jti) {
        String blacklistKey = "blacklist:" + jti;
        String sessionKey = "auth:session:" + userId + ":" + jti;
        
        String script = 
            "if redis.call('exists', KEYS[1]) == 1 then return 0 end " +
            "if redis.call('exists', KEYS[2]) == 1 then return 1 end " +
            "return 0";
            
        Long result = redisTemplate.execute(
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
            List.of(blacklistKey, sessionKey)
        );
        
        return Boolean.TRUE.equals(result != null && result == 1L);
    }

    @Override
    public void markAsBlacklisted(String jti, long ttlSeconds) {
        redisTemplate.opsForValue().set("blacklist:" + jti, Boolean.TRUE, java.time.Duration.ofSeconds(ttlSeconds));
    }
}
