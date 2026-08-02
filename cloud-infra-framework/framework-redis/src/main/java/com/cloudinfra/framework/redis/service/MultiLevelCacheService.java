package com.cloudinfra.framework.redis.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 多级缓存服务
 * L1: Caffeine 本地缓存（纳秒级延迟）
 * L2: Redis 分布式缓存（毫秒级延迟）
 * 
 * 读取流程：L1 -> L2 -> 回源
 * 写入流程：双写 L1 + L2
 * 失效流程：双删 L1 + L2
 */
@Service
public class MultiLevelCacheService {

    private static final Logger log = LoggerFactory.getLogger(MultiLevelCacheService.class);

    private final RedisTemplate<String, Object> redisTemplate;
    
    // L1 缓存实例（按名称隔离）
    private final Cache<String, Object> defaultL1Cache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .expireAfterAccess(Duration.ofMinutes(10))
        .recordStats()
        .build();

    public MultiLevelCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 获取缓存（L1 -> L2 -> 回源）
     */
    public <T> Optional<T> get(String cacheName, String key, Class<T> clazz) {
        // 1. 尝试 L1 缓存
        Cache<String, Object> l1Cache = getOrCreateL1Cache(cacheName);
        Object l1Value = l1Cache.getIfPresent(key);
        if (l1Value != null) {
            log.debug("L1 缓存命中: {}:{}", cacheName, key);
            return Optional.of(clazz.cast(l1Value));
        }

        // 2. 尝试 L2 (Redis)
        String redisKey = buildRedisKey(cacheName, key);
        Object l2Value = redisTemplate.opsForValue().get(redisKey);
        if (l2Value != null) {
            // 回填 L1
            l1Cache.put(key, l2Value);
            log.debug("L2 缓存命中，回填 L1: {}:{}", cacheName, key);
            return Optional.of(clazz.cast(l2Value));
        }

        log.debug("缓存未命中: {}:{}", cacheName, key);
        return Optional.empty();
    }

    /**
     * 设置缓存（双写 L1 + L2）
     */
    public void put(String cacheName, String key, Object value, Duration ttl) {
        // 写入 L1
        Cache<String, Object> l1Cache = getOrCreateL1Cache(cacheName);
        l1Cache.put(key, value);

        // 写入 L2 (Redis)
        String redisKey = buildRedisKey(cacheName, key);
        redisTemplate.opsForValue().set(redisKey, value, ttl);
        
        log.debug("双写缓存: {}:{}", cacheName, key);
    }

    /**
     * 设置缓存（使用默认 TTL）
     */
    public void put(String cacheName, String key, Object value) {
        put(cacheName, key, value, Duration.ofMinutes(10));
    }

    /**
     * 删除缓存（双删 L1 + L2）
     */
    public void evict(String cacheName, String key) {
        // 删除 L1
        Cache<String, Object> l1Cache = getOrCreateL1Cache(cacheName);
        l1Cache.invalidate(key);

        // 删除 L2
        String redisKey = buildRedisKey(cacheName, key);
        redisTemplate.delete(redisKey);
        
        log.debug("双删缓存: {}:{}", cacheName, key);
    }

    /**
     * 批量删除
     */
    public void evictAll(String cacheName, Iterable<String> keys) {
        Cache<String, Object> l1Cache = getOrCreateL1Cache(cacheName);
        for (String key : keys) {
            l1Cache.invalidate(key);
            String redisKey = buildRedisKey(cacheName, key);
            redisTemplate.delete(redisKey);
        }
    }

    /**
     * 清空指定缓存区域
     */
    public void clear(String cacheName) {
        Cache<String, Object> l1Cache = getOrCreateL1Cache(cacheName);
        l1Cache.invalidateAll();
        
        // Redis 使用 pattern 删除（注意生产环境慎用）
        String pattern = buildRedisKey(cacheName, "*");
        redisTemplate.keys(pattern).forEach(redisTemplate::delete);
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats(String cacheName) {
        Cache<String, Object> l1Cache = getOrCreateL1Cache(cacheName);
        var stats = l1Cache.stats();
        return new CacheStats(
            cacheName,
            stats.hitCount(),
            stats.missCount(),
            stats.hitRate(),
            l1Cache.estimatedSize(),
            stats.evictionCount()
        );
    }

    /**
     * 获取或创建 L1 缓存实例
     */
    private Cache<String, Object> getOrCreateL1Cache(String cacheName) {
        // 这里简化处理，实际可根据 cacheName 返回不同配置的缓存
        return defaultL1Cache;
    }

    /**
     * 构建 Redis Key
     */
    private String buildRedisKey(String cacheName, String key) {
        return "cloud-infra:" + cacheName + ":" + key;
    }

    /**
     * 缓存统计记录
     */
    public record CacheStats(
        String cacheName,
        long hitCount,
        long missCount,
        double hitRate,
        long estimatedSize,
        long evictionCount
    ) {
        public long totalRequests() {
            return hitCount + missCount;
        }
    }
}
