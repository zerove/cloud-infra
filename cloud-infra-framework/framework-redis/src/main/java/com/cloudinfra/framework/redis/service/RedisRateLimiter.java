package com.cloudinfra.framework.redis.service;

import jakarta.annotation.Resource;
import java.util.Collections;
import org.springframework.stereotype.Service;

/**
 * 分布式限流器
 */
@Service
public class RedisRateLimiter {

    @Resource
    private RedisService redisService;

    /**
     * 使用令牌桶算法进行限流
     * @param key 限流键
     * @param limit 限制次数
     * @param period 时间窗口(秒)
     * @return 是否允许访问
     */
    public boolean isAllowed(String key, int limit, int period) {
        String script =
            "local key = KEYS[1] " +
            "local limit = tonumber(ARGV[1]) " +
            "local period = tonumber(ARGV[2]) " +
            "local current = redis.call('get', key) " +
            "if current and tonumber(current) >= limit then " +
            "    return 0 " +
            "end " +
            "redis.call('incr', key) " +
            "if not current then " +
            "    redis.call('expire', key, period) " +
            "end " +
            "return 1";

        Long result = redisService.executeLuaScript(
            script,
            Collections.singletonList(key),
            limit,
            period
        );
        return result != null && result == 1;
    }
}
