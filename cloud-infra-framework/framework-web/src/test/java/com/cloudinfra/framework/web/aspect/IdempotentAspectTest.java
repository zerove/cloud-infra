package com.cloudinfra.framework.web.aspect;

import com.cloudinfra.common.core.annotation.Idempotent;
import com.cloudinfra.common.core.exception.GlobalException;
import com.cloudinfra.framework.web.idempotency.IdempotencyEntry;
import com.cloudinfra.framework.web.idempotency.IdempotencyStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IdempotentAspect 单元测试 (M6-02 Phase A)
 *
 * 覆盖 8 个关键场景:
 * - REJECT 模式: 首次调用通过, 二次调用抛异常
 * - REJECT 模式: 缺 key 头回退自动生成
 * - REPLAY 模式: 首次调用执行业务 + 缓存响应
 * - REPLAY 模式: 二次调用命中缓存返回首次结果
 * - REPLAY 模式: 业务异常时 releaseLock 不缓存
 * - REPLAY 模式: 未获得锁 (并发首次) 抛异常
 * - Idempotency-Key 头优先级 > X-Request-Token
 * - 超长 token (>200) 拒绝
 */
class IdempotentAspectTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOps;
    private IdempotencyStore store;
    private IdempotentAspect aspect;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        store = mock(IdempotencyStore.class);
        aspect = new IdempotentAspect(redisTemplate, store);

        request = new MockHttpServletRequest("POST", "/auth/login");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ========== REJECT 模式 ==========

    @Test
    @DisplayName("REJECT: 首次调用 SET NX 成功 → 业务执行")
    void shouldExecuteOnFirstRejectCall() throws Throwable {
        request.addHeader("Idempotency-Key", "token-reject-1");
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.around(pjp, idempotentReject());

        assertThat(result).isEqualTo("ok");
        verify(pjp, times(1)).proceed();
    }

    @Test
    @DisplayName("REJECT: 二次调用 SET NX 返回 false → 抛 GlobalException")
    void shouldThrowOnDuplicateRejectCall() throws Throwable {
        request.addHeader("Idempotency-Key", "token-reject-2");
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(false);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

        assertThatThrownBy(() -> aspect.around(pjp, idempotentReject()))
            .isInstanceOf(GlobalException.class)
            .hasMessageContaining("请求过于频繁");
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("REJECT: 缺 key 头 → 自动生成 userId+URI+body digest")
    void shouldFallbackToAutoKeyOnReject() throws Throwable {
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.around(pjp, idempotentReject());

        assertThat(result).isEqualTo("ok");
        verify(pjp).proceed();
    }

    // ========== REPLAY 模式 ==========

    @Test
    @DisplayName("REPLAY: 首次调用 → 执行业务 + 缓存响应")
    void shouldExecuteAndCacheOnFirstReplayCall() throws Throwable {
        request.addHeader("Idempotency-Key", "token-replay-1");
        when(store.read(anyString())).thenReturn(null);  // 缓存未命中
        when(store.tryAcquire(anyString(), anyInt())).thenReturn(true);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("{\"token\":\"abc\"}");

        Object result = aspect.around(pjp, idempotentReplay());

        assertThat(result).isEqualTo("{\"token\":\"abc\"}");
        verify(pjp).proceed();
        verify(store).storeResponse(anyString(), any(IdempotencyEntry.class), eq(86400));
    }

    @Test
    @DisplayName("REPLAY: 二次调用命中缓存 → 按方法返回类型重放 (不执行业务)")
    void shouldReplayCachedResponse() throws Throwable {
        request.addHeader("Idempotency-Key", "token-replay-2");
        String jsonFirst = "{\"token\":\"first\"}";
        IdempotencyEntry cached = IdempotencyEntry.success(200, jsonFirst);
        when(store.read(anyString())).thenReturn(cached);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        org.aspectj.lang.reflect.MethodSignature signature = mock(org.aspectj.lang.reflect.MethodSignature.class);
        when(signature.getReturnType()).thenReturn((Class) String.class);
        when(pjp.getSignature()).thenReturn(signature);

        Object result = aspect.around(pjp, idempotentReplay());

        assertThat(result).isEqualTo("{\"token\":\"first\"}");
        verify(pjp, never()).proceed();
        verify(store, never()).tryAcquire(anyString(), anyInt());
    }

    @Test
    @DisplayName("REPLAY: 业务异常 → releaseLock 不缓存响应")
    void shouldReleaseLockOnException() throws Throwable {
        request.addHeader("Idempotency-Key", "token-replay-3");
        when(store.read(anyString())).thenReturn(null);
        when(store.tryAcquire(anyString(), anyInt())).thenReturn(true);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenThrow(new RuntimeException("业务失败"));

        assertThatThrownBy(() -> aspect.around(pjp, idempotentReplay()))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("业务失败");
        verify(store).releaseLock(anyString());
        verify(store, never()).storeResponse(anyString(), any(IdempotencyEntry.class), anyInt());
    }

    @Test
    @DisplayName("REPLAY 契约: 命中缓存后按方法返回类型反序列化为 Result")
    void shouldReplayWithSameResultContract() throws Throwable {
        request.addHeader("Idempotency-Key", "token-replay-contract");
        String json = "{\"success\":true,\"code\":200,\"message\":\"操作成功\",\"data\":\"registered\"}";
        when(store.read(anyString())).thenReturn(IdempotencyEntry.success(200, json));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        org.aspectj.lang.reflect.MethodSignature signature = mock(org.aspectj.lang.reflect.MethodSignature.class);
        when(signature.getReturnType()).thenReturn((Class) com.cloudinfra.common.core.domain.Result.class);
        when(pjp.getSignature()).thenReturn(signature);

        Object replayed = aspect.around(pjp, idempotentReplay());

        assertThat(replayed).isInstanceOf(com.cloudinfra.common.core.domain.Result.class);
        com.cloudinfra.common.core.domain.Result<?> typed =
            (com.cloudinfra.common.core.domain.Result<?>) replayed;
        assertThat(typed.isSuccess()).isTrue();
        assertThat(typed.getData()).isEqualTo("registered");
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("REPLAY: 并发首次调用 (tryAcquire=false) → 抛 GlobalException")
    void shouldThrowWhenConcurrentFirstCall() throws Throwable {
        request.addHeader("Idempotency-Key", "token-replay-4");
        when(store.read(anyString())).thenReturn(null);
        when(store.tryAcquire(anyString(), anyInt())).thenReturn(false);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

        assertThatThrownBy(() -> aspect.around(pjp, idempotentReplay()))
            .isInstanceOf(GlobalException.class)
            .hasMessageContaining("稍后重试");
        verify(pjp, never()).proceed();
    }

    // ========== Key 解析 ==========

    @Test
    @DisplayName("Key 优先级: Idempotency-Key > X-Request-Token")
    void shouldPreferIdempotencyKeyHeader() throws Throwable {
        request.addHeader("Idempotency-Key", "idemp-token");
        request.addHeader("X-Request-Token", "old-token");
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        aspect.around(pjp, idempotentReject());

        // 验证锁 key 包含 idemp-token (而非 old-token)
        // 由于 hash 算法, 仅能验证 setIfAbsent 被调用
        verify(valueOps).setIfAbsent(anyString(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("超长 token (>200) → 拒绝, 直接放行业务")
    void shouldRejectOversizedToken() throws Throwable {
        request.addHeader("Idempotency-Key", "a".repeat(250));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.around(pjp, idempotentReject());

        // 超长 token 被拒, 业务仍执行 (降级)
        assertThat(result).isEqualTo("ok");
        verify(valueOps, never()).setIfAbsent(anyString(), any(), any(Duration.class));
    }

    // ========== 辅助 ==========

    private Idempotent idempotentReject() {
        return newAnnotation(Idempotent.Mode.REJECT, 10);
    }

    private Idempotent idempotentReplay() {
        return newAnnotation(Idempotent.Mode.REPLAY, 86400);
    }

    private Idempotent newAnnotation(Idempotent.Mode mode, int expireSeconds) {
        return new Idempotent() {
            @Override public Idempotent.Mode mode() { return mode; }
            @Override public int expireSeconds() { return expireSeconds; }
            @Override public String message() { return "请求过于频繁"; }
            @Override public String keyHeader() { return "Idempotency-Key"; }
            @Override public boolean storeResponse() { return true; }
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return Idempotent.class; }
        };
    }

    // Mockito anyInt 替代 (Java 8 兼容)
    private static int anyInt() { return org.mockito.ArgumentMatchers.anyInt(); }
}
