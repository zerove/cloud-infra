package com.cloudinfra.framework.redis.service;

import jakarta.annotation.Resource;
import java.util.Collections;
import org.springframework.stereotype.Service;

/**
 * 分布式令牌桶限流器
 *
 * 以 Redis 原子 Lua 脚本实现令牌桶：
 * - 桶容量 = limit，每秒补充 rate = limit / period 个令牌
 * - 首次访问时按当前时间补齐令牌（冷启动即满桶）
 * - 令牌不足（桶空）时拒绝请求
 *
 * @param key    限流键（不同 key 相互隔离）
 * @param limit  桶容量（允许的突发请求数）
 * @param period 令牌补充周期（秒），补充速率 = limit / period（个/秒）
 * @return 是否允许访问
 */
@Service
public class RedisRateLimiter {

    private static final String BUCKET_SCRIPT =
        // KEYS[1]: bucket key
        // ARGV[1]: limit (capacity), ARGV[2]: refill rate (tokens/sec)
        "local key = KEYS[1] " +
        "local capacity = tonumber(ARGV[1]) " +
        "local rate = tonumber(ARGV[2]) " +
        "local now = redis.call('TIME')[1] " +
        "local bucket = redis.call('HMGET', key, 'tokens', 'ts') " +
        "local tokens = tonumber(bucket[1]) " +
        "local ts = tonumber(bucket[2]) " +
        "if tokens == nil then " +
        "    tokens = capacity " +
        "    ts = now " +
        "else " +
        "    tokens = math.min(capacity, tokens + (now - ts) * rate) " +
        "    ts = now " +
        "end " +
        "local allowed = 0 " +
        "if tokens >= 1 then " +
        "    tokens = tokens - 1 " +
        "    allowed = 1 " +
        "end " +
        "redis.call('HMSET', key, 'tokens', tokens, 'ts', ts) " +
        "redis.call('expire', key, 2 * ARGV[3]) " +
        "return allowed";

    @Resource
    private RedisService redisService;

    /**
     * 使用令牌桶算法进行限流
     * @param key 限流键
     * @param limit 桶容量（也作为上限）
     * @param period 时间窗口(秒)；补充速率 = limit / period
     * @return 是否允许访问
     */
    public boolean isAllowed(String key, int limit, int period) {
        if (limit <= 0 || period <= 0) {
            return false;
        }
        // 补充速率 = limit / period（个/秒）
        double rate = (double) limit / period;
        Long result = redisService.executeLuaScript(
            BUCKET_SCRIPT,
            Collections.singletonList(key),
            limit,
            String.valueOf(rate),
            period
        );
        return result != null && result == 1;
    }
}