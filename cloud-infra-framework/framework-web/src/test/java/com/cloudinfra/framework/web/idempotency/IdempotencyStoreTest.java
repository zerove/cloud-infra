package com.cloudinfra.framework.web.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IdempotencyStore 单元测试 (M6-02 Phase A)
 *
 * 覆盖 8 个关键路径:
 * - tryAcquire 首次获得锁
 * - tryAcquire 二次失败 (SET NX 返回 false)
 * - tryAcquire Redis 异常降级
 * - read 命中 COMPLETED 响应
 * - read 未命中 (status != COMPLETED)
 * - read Redis 异常返回 null
 * - storeResponse 写 status + response
 * - releaseLock 删 lock + status
 */
class IdempotencyStoreTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOps;
    private IdempotencyStore store;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        store = new IdempotencyStore(redisTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("tryAcquire: 首次调用 SET NX 成功 → 返回 true")
    void shouldAcquireOnFirstCall() {
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);

        boolean acquired = store.tryAcquire("hash-001", 60);

        assertThat(acquired).isTrue();
        verify(valueOps).setIfAbsent(eq("idempotency:hash-001:lock"), eq("1"), any(Duration.class));
        verify(valueOps).set(eq("idempotency:hash-001:status"), eq("IN_FLIGHT"), any(Duration.class));
    }

    @Test
    @DisplayName("tryAcquire: 二次调用 SET NX 返回 false → 返回 false")
    void shouldFailToAcquireOnDuplicate() {
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(false);

        boolean acquired = store.tryAcquire("hash-002", 60);

        assertThat(acquired).isFalse();
    }

    @Test
    @DisplayName("tryAcquire: Redis 异常 → 降级返回 true (放行)")
    void shouldFallbackOnRedisError() {
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class)))
            .thenThrow(new RuntimeException("Redis 连接失败"));

        boolean acquired = store.tryAcquire("hash-003", 60);

        assertThat(acquired).isTrue();  // 降级放行
    }

    @Test
    @DisplayName("read: status=COMPLETED + response 命中 → 反序列化为 IdempotencyEntry")
    void shouldReadCompletedResponse() {
        when(valueOps.get("idempotency:hash-004:status")).thenReturn("COMPLETED");
        Map<String, Object> cached = new HashMap<>();
        cached.put("statusCode", 200);
        cached.put("body", "{\"token\":\"abc123\"}");
        when(valueOps.get("idempotency:hash-004:response")).thenReturn(cached);

        IdempotencyEntry entry = store.read("hash-004");

        assertThat(entry).isNotNull();
        assertThat(entry.isSuccess()).isTrue();
        assertThat(entry.getStatusCode()).isEqualTo(200);
        assertThat(entry.getBody()).isEqualTo("{\"token\":\"abc123\"}");
    }

    @Test
    @DisplayName("read: status=IN_FLIGHT → 返回 null (业务执行中)")
    void shouldReturnNullWhenInFlight() {
        when(valueOps.get("idempotency:hash-005:status")).thenReturn("IN_FLIGHT");

        IdempotencyEntry entry = store.read("hash-005");

        assertThat(entry).isNull();
    }

    @Test
    @DisplayName("read: Redis 异常 → 返回 null")
    void shouldReturnNullOnRedisError() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        IdempotencyEntry entry = store.read("hash-006");

        assertThat(entry).isNull();
    }

    @Test
    @DisplayName("storeResponse: 写 status=COMPLETED + response entry")
    void shouldStoreResponseAndStatus() {
        IdempotencyEntry entry = IdempotencyEntry.success(200, "{\"id\":42}");

        store.storeResponse("hash-007", entry, 3600);

        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(valueOps, times(2)).set(anyString(), valueCaptor.capture(), any(Duration.class));
        // 第一次 set = response, 第二次 set = status
        assertThat(valueCaptor.getAllValues()).contains(entry, "COMPLETED");
    }

    @Test
    @DisplayName("releaseLock: 删 lock + status key")
    void shouldReleaseLock() {
        store.releaseLock("hash-008");

        verify(redisTemplate, times(2)).delete(anyString());
        verify(redisTemplate).delete("idempotency:hash-008:lock");
        verify(redisTemplate).delete("idempotency:hash-008:status");
    }
}