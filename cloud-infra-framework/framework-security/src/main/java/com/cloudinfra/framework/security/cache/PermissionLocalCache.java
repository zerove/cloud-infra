package com.cloudinfra.framework.security.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 权限链路 L1 本地缓存（F4）。
 *
 * <p>HeaderAuthenticationFilter 每请求经 @Cacheable 走 Redis（L2），
 * 热点用户高 QPS 时 Redis 成为瓶颈。本缓存置于 Cacheable 之前：
 * L1 命中即免 Redis 往返。</p>
 *
 * <p>一致性设计：L1 TTL 30 秒，是"失效广播跨实例传播窗口 + 本实例
 * 清理延迟"的上界；监听器收到 user:{uid} 失效消息时同步 evict 本地条目，
 * 广播丢失场景由短 TTL 兜底。</p>
 */
@Component
public class PermissionLocalCache {

    private static final Logger log = LoggerFactory.getLogger(PermissionLocalCache.class);

    /** 键: userId, 值: CachedEntry（permissions 或 LoginUser 上下文） */
    private final Cache<Long, Object> cache = Caffeine.newBuilder()
        .maximumSize(5_000)
        .expireAfterWrite(Duration.ofSeconds(30))
        .recordStats()
        .build();

    /**
     * 读本地条目；命中返回 null 表示未命中。
     * 用包装类区分"缓存了 null 值"与"未命中"，避免负查反复穿透。
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Long userId, Class<T> type) {
        Object v = cache.getIfPresent(userId);
        if (v instanceof NullMarker) {
            return type.cast(null);
        }
        return v == null ? null : (T) v;
    }

    public boolean hasEntry(Long userId) {
        return cache.getIfPresent(userId) != null;
    }

    public void put(Long userId, Object value) {
        cache.put(userId, value == null ? NullMarker.INSTANCE : value);
    }

    /** 失效广播到达时清单用户；全量消息清空。 */
    public void evict(Long userId) {
        cache.invalidate(userId);
    }

    public void evictAll() {
        cache.invalidateAll();
        log.info("权限 L1 本地缓存已全量清空");
    }

    public long estimatedSize() {
        return cache.estimatedSize();
    }

    /** null 占位标记：防止不存在的用户反复打穿到 L2/DB。 */
    private static final class NullMarker {
        static final NullMarker INSTANCE = new NullMarker();
    }
}
