package com.cloudinfra.framework.redis.service;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RedisService {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void set(String key, Object value, long expireTime) {
        redisTemplate
            .opsForValue()
            .set(key, value, expireTime, TimeUnit.MILLISECONDS);
    }

    public boolean deleteObject(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        return (T) value;
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey("blacklist:" + jti));
    }

    public Long executeLuaScript(
        String script,
        List<String> keys,
        Object... args
    ) {
        return redisTemplate.execute(
            new DefaultRedisScript<>(script, Long.class),
            keys,
            args
        );
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    /** 批量删除; 空集合直接返回 false, 避免向 Redis 发空命令。 */
    public boolean delete(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            // 空集合短路返回, 不向 Redis 发起空 DEL 命令
            return false;
        }
        // Spring Data Redis 4.1: delete(Collection) 返回 Long(实际删除的 key 数),
        // 与单 key delete(K) 的 Boolean 语义不同, 二者不可混用。
        Long deletedCount = redisTemplate.delete(keys);
        return deletedCount != null && deletedCount > 0;
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public Long incr(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }

    public boolean expire(String key, long timeout) {
        return Boolean.TRUE.equals(
            redisTemplate.expire(key, timeout, TimeUnit.MILLISECONDS)
        );
    }

    public void publish(String channel, Object message) {
        redisTemplate.convertAndSend(channel, message);
    }
}
