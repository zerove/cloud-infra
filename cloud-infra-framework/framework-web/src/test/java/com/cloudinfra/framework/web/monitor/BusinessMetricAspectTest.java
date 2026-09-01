package com.cloudinfra.framework.web.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudinfra.common.core.domain.Result;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * BusinessMetric 单元测试 (M5-03, 2026-09-01)
 *
 * <p>验证:
 * <ul>
 *   <li>Counter 按需创建并复用</li>
 *   <li>@Around 在 proceed 成功时 increment 正确</li>
 *   <li>@Around 在 proceed 抛异常时 increment failure + 异常原样传播</li>
 *   <li>Result&lt;?&gt; 返回值智能判断 success/failure</li>
 * </ul>
 */
class BusinessMetricAspectTest {

    private MeterRegistry registry;
    private BusinessMetricAspect aspect;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        aspect = new BusinessMetricAspect(registry);
    }

    private void invokeIncrement(String metric, String tagKey, String tagValue) {
        try {
            var m = BusinessMetricAspect.class.getDeclaredMethod(
                "increment", String.class, String.class, String.class);
            m.setAccessible(true);
            m.invoke(aspect, metric, tagKey, tagValue);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testCounterCreatedOnDemand() {
        invokeIncrement("test.metric", "tag", "v1");
        Counter c = registry.find("test.metric").tag("tag", "v1").counter();
        assertNotNull(c, "Counter 必须按需创建");
        assertEquals(1.0, c.count(), "increment 1 次后 count=1");
    }

    @Test
    void testSameCounterReused() {
        invokeIncrement("test.metric", "tag", "v1");
        invokeIncrement("test.metric", "tag", "v1");
        Counter c = registry.find("test.metric").tag("tag", "v1").counter();
        assertEquals(2.0, c.count(), "两次 increment 应累计到同一 Counter");
    }

    @Test
    void testResultSuccessIncrementsSuccessCounter() {
        invokeIncrement("auth.login.attempts", "result", "success");
        invokeIncrement("auth.login.attempts", "result", "success");
        Counter c = registry.find("auth.login.attempts").tag("result", "success").counter();
        assertNotNull(c);
        assertEquals(2.0, c.count());
    }

    @Test
    void testResultFailureIncrementsFailureCounter() {
        invokeIncrement("auth.login.attempts", "result", "failure");
        Counter c = registry.find("auth.login.attempts").tag("result", "failure").counter();
        assertNotNull(c);
        assertEquals(1.0, c.count());
    }

    @Test
    void testFileUploadFailureTag() {
        invokeIncrement("file.upload.failures", "reason", "upload");
        Counter c = registry.find("file.upload.failures").tag("reason", "upload").counter();
        assertNotNull(c, "file.upload.failures{reason=upload} 必须被注册");
        assertEquals(1.0, c.count());
    }

    @Test
    void testResultAwareSuccess() throws Throwable {
        BusinessMetric bm = makeMetric("test.method", "test", "success", "failure");
        ProceedingJoinPoint pjp = mockPjp(Result.success("ok"));
        Object result = aspect.track(pjp, bm);
        assertNotNull(result);
        Counter c = registry.find("test.method").tag("result", "success").counter();
        assertNotNull(c, "Result.success 应 increment success counter");
        assertEquals(1.0, c.count());
    }

    @Test
    void testResultAwareFailure() throws Throwable {
        BusinessMetric bm = makeMetric("test.method", "test", "success", "failure");
        ProceedingJoinPoint pjp = mockPjp(Result.fail(500, "err"));
        Object result = aspect.track(pjp, bm);
        assertNotNull(result);
        Counter c = registry.find("test.method").tag("result", "failure").counter();
        assertNotNull(c, "Result.fail 应 increment failure counter");
        assertEquals(1.0, c.count());
    }

    @Test
    void testAroundPropagatesException() throws Throwable {
        BusinessMetric bm = makeMetric("test.method", "test", "", "failure");
        IllegalArgumentException expected = new IllegalArgumentException("test error");
        ProceedingJoinPoint pjp = Mockito.mock(ProceedingJoinPoint.class);
        Mockito.when(pjp.proceed()).thenThrow(expected);

        IllegalArgumentException actual = assertThrows(
            IllegalArgumentException.class,
            () -> aspect.track(pjp, bm)
        );
        assertSame(expected, actual, "原异常应原样传播");
    }

    private BusinessMetric makeMetric(String metric, String reason, String onSuccess, String onFailure) {
        return new BusinessMetric() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return BusinessMetric.class;
            }
            @Override public String metric() { return metric; }
            @Override public String reason() { return reason; }
            @Override public String resultOnSuccess() { return onSuccess; }
            @Override public String resultOnFailure() { return onFailure; }
        };
    }

    private ProceedingJoinPoint mockPjp(Object returnValue) throws Throwable {
        ProceedingJoinPoint pjp = Mockito.mock(ProceedingJoinPoint.class);
        Mockito.when(pjp.proceed()).thenReturn(returnValue);
        Signature sig = Mockito.mock(Signature.class);
        Mockito.when(sig.toShortString()).thenReturn("Mock.method");
        Mockito.when(pjp.getSignature()).thenReturn(sig);
        return pjp;
    }
}
