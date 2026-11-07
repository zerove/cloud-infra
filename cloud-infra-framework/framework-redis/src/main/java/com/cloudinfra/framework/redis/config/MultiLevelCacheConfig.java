package com.cloudinfra.framework.redis.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
 *
 * 设计原则 (AGENTS.md 审查报告 Q5): Redis 所有模块默认共享 database=0，
 * 会随模块扩展出现跨模块缓存名冲突与误删风险。本类通过 Nacos 配置
 * (spring.redis.database:<cacheName>) 显式为每个缓存命名空间映射到独立 DB，
 * 默认值遵循 "auth/permission/token → 0; 业务态 → 1 起" 的分片规则，
 * 各 team 在 Nacos overrides 中可覆盖该规则。
 */
@Configuration
@EnableCaching
public class MultiLevelCacheConfig {

    /**
     * 缓存命名空间 → 默认 Redis database 的分片映射。
     * 仅作为默认值，可被 Nacos spring.redis.database.<cacheName> 覆盖。
     */
    private static final Map<String, Integer> DEFAULT_DB_SHARD = Map.of(
            // 第一库: 认证/Token 类高频热点
            "permissions", 0,
            "userContexts", 0,
            "sessions", 0,
            // 第二库起: 业务态表缓存
            "dictionaries", 1,
            "rate-limit", 1,
            // 第三库起: 系统配置/字典等长 TTL
            "config", 2,
            "file:content", 3,
            "notification:inbox", 4
    );

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "caffeineCacheManager")
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .expireAfterAccess(Duration.ofMinutes(10))
            .recordStats()
            // 注意：不可用 weakKeys()——弱引用模式改用 ==（标识比较），
            // 而 @Cacheable 的 key 是每次装箱的 Long userId，超过 -128~127
            // 缓存池后 != 恒成立，L1 将永久 miss 退化为纯穿透
        );
        return cacheManager;
    }

    @Bean
    @ConditionalOnMissingBean(name = "redisCacheManager")
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues()
            .prefixCacheNameWith("cloud-infra:");

        // 不同缓存区域的差异化配置 (见 DEFAULT_DB_SHARD 注释: 分片语义预留)
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        for (var entry : DEFAULT_DB_SHARD.entrySet()) {
            cacheConfigurations.put(entry.getKey(), defaultConfig);
        }

        cacheConfigurations.putIfAbsent("config", defaultConfig.entryTtl(Duration.ofHours(1)));

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigurations)
            .build();
    }
}
