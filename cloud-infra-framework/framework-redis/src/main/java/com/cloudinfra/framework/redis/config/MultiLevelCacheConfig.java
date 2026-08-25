package com.cloudinfra.framework.redis.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 多级缓存配置
 * L1: Caffeine 本地缓存（极速、容量小）
 * L2: Redis 分布式缓存（容量大、跨实例共享）
 */
@Configuration
@EnableCaching
public class MultiLevelCacheConfig {

    /**
     * L1 缓存：Caffeine 本地缓存
     * - 最大 10000 条目
     * - 5 分钟过期
     * - 写入后 10 分钟过期
     * - 记录统计信息
     */
    @Bean
    @Primary
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .expireAfterAccess(Duration.ofMinutes(10))
            .recordStats()
            .weakKeys() // 允许 GC 回收 key
        );
        return cacheManager;
    }

    /**
     * L2 缓存：Redis 分布式缓存
     * - 默认 10 分钟 TTL
     * - 禁用缓存空值
     * - 使用前缀隔离
     */
    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .disableCachingNullValues()
            .prefixCacheNameWith("cloud-infra:");

        // 不同缓存区域的差异化配置
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        
        // 用户会话：长 TTL
        cacheConfigurations.put("sessions", defaultConfig.entryTtl(Duration.ofHours(24)));
        
        // 权限缓存：短 TTL。主动失效走 auth:refresh:channel 广播，但 Redis pub/sub
        // 发后即忘，监听方重启/抖动期间的消息会丢；TTL 是唯一兜底，须保持分钟级
        cacheConfigurations.put("permissions", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        
        // 字典/配置：长 TTL
        cacheConfigurations.put("dictionaries", defaultConfig.entryTtl(Duration.ofHours(1)));
        
        // 限流计数器：短 TTL
        cacheConfigurations.put("rate-limit", defaultConfig.entryTtl(Duration.ofMinutes(1)));

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigurations)
            .build();
    }
}