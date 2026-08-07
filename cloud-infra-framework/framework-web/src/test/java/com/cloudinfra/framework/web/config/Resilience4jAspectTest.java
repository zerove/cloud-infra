package com.cloudinfra.framework.web.config;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Resilience4j {@code @Retry}/{@code @CircuitBreaker} 注解切面在
 * Spring AOP 代理 Bean 上真实生效(由 {@link Resilience4jAutoConfiguration}
 * 导入官方自动配置注册切面)。
 */
@SpringBootTest(classes = Resilience4jAspectTest.TestConfig.class, properties = {
        "resilience4j.retry.instances.testRetry.max-attempts=3",
        "resilience4j.retry.instances.testRetry.wait-duration=10ms",
        "resilience4j.retry.instances.testRetry.retry-exceptions=java.io.IOException",
        "resilience4j.circuitbreaker.instances.testCircuit.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.testCircuit.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.testCircuit.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.testCircuit.wait-duration-in-open-state=1s"
})
class Resilience4jAspectTest {

    @SpringBootConfiguration
    @Import({AopAutoConfiguration.class, Resilience4jAutoConfiguration.class, TestService.class})
    static class TestConfig {
    }

    @Autowired
    private TestService testService;

    @Test
    void retryAnnotation_shouldRetryUntilSuccess() throws IOException {
        testService.reset();
        String result = testService.flakyRetry();
        assertThat(result).isEqualTo("ok");
        // 2 次失败 + 1 次成功 = 3 次调用
        assertThat(testService.attempts()).isEqualTo(3);
    }

    @Test
    void retryAnnotation_shouldExhaustAfterMaxAttempts() {
        testService.reset();
        assertThatThrownBy(testService::alwaysFailRetry)
                .isInstanceOf(IOException.class);
        // max-attempts=3,全部失败
        assertThat(testService.attempts()).isEqualTo(3);
    }

    @Test
    void circuitBreaker_shouldOpenAfterMinimumFailures() {
        testService.reset();
        // 连续失败 2 次(达到 minimum-number-of-calls)
        for (int i = 0; i < 2; i++) {
            try {
                testService.flakyCircuit();
            } catch (IOException ignored) {
                // 预期失败
            }
        }
        // 熔断打开,后续调用直接拒绝
        assertThatThrownBy(testService::flakyCircuit)
                .isInstanceOf(CallNotPermittedException.class);
    }

    @Service
    public static class TestService {
        private final AtomicInteger attempts = new AtomicInteger();

        @Retry(name = "testRetry")
        public String flakyRetry() throws IOException {
            if (attempts.incrementAndGet() < 3) {
                throw new IOException("transient failure");
            }
            return "ok";
        }

        @Retry(name = "testRetry")
        public String alwaysFailRetry() throws IOException {
            attempts.incrementAndGet();
            throw new IOException("persistent failure");
        }

        @CircuitBreaker(name = "testCircuit")
        public String flakyCircuit() throws IOException {
            attempts.incrementAndGet();
            throw new IOException("circuit failure");
        }

        void reset() {
            attempts.set(0);
        }

        int attempts() {
            return attempts.get();
        }
    }
}
