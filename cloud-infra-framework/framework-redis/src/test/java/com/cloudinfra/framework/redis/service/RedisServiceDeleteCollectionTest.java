package com.cloudinfra.framework.redis.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RedisService#delete(java.util.Collection)} 的返回值语义回归测试。
 *
 * <p>Spring Data Redis 4.1 将 {@code delete(Collection)} 的返回类型从
 * {@code Boolean} 改为 {@code Long}(实际删除的 key 数)。旧实现用
 * {@code Boolean.TRUE.equals(...)} 比较, 对任何非 null 的 Long 都返回 false ——
 * 批量删除永远"失败"。本测试固定住 Long 语义, 防止回退。
 */
class RedisServiceDeleteCollectionTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);

    private final RedisService redisService = new RedisService(redisTemplate);

    @Test
    @DisplayName("批量删除至少一个 key 时返回 true")
    void returnsTrueWhenAtLeastOneKeyDeleted() {
        List<String> keys = List.of("a", "b");
        when(redisTemplate.delete(keys)).thenReturn(2L);

        assertThat(redisService.delete(keys)).isTrue();
    }

    @Test
    @DisplayName("批量删除 0 个 key 时返回 false")
    void returnsFalseWhenNothingDeleted() {
        List<String> keys = List.of("missing");
        when(redisTemplate.delete(keys)).thenReturn(0L);

        assertThat(redisService.delete(keys)).isFalse();
    }

    @Test
    @DisplayName("Redis 返回 null 时返回 false(不抛 NPE)")
    void returnsFalseWhenRedisReturnsNull() {
        List<String> keys = List.of("a");
        when(redisTemplate.delete(keys)).thenReturn(null);

        assertThat(redisService.delete(keys)).isFalse();
    }

    @Test
    @DisplayName("空集合 / null 直接返回 false, 不访问 Redis")
    void shortCircuitsEmptyInput() {
        assertThat(redisService.delete(Collections.emptyList())).isFalse();
        assertThat(redisService.delete((Collection<String>) null)).isFalse();

        verifyNoInteractions(redisTemplate);
    }
}
