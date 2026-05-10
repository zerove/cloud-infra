package com.cloudinfra.framework.redis.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Service
public class RedisService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    public void addToBlacklist(String jti, long expireTime) {
        redisTemplate.opsForValue().set("token:blacklist:" + jti, "1", expireTime, TimeUnit.MILLISECONDS);
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("token:blacklist:" + jti));
    }

    public void set(String key, Object value, long expireTime) {
        redisTemplate.opsForValue().set(key, value, expireTime, TimeUnit.MILLISECONDS);
    }

    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 执行 Lua 脚本
     */
    public <T> T executeLuaScript(String script, java.util.List<String> keys, Object... args) {
        org.springframework.data.redis.core.script.DefaultRedisScript<T> redisScript = new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType((Class<T>) args[0].getClass()); // 简化处理，实际生产需更严谨
        return redisTemplate.execute(redisScript, keys, args);
    }
}
