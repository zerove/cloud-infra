package com.cloudinfra.framework.web.config;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;

/**
 * Resilience4j 弹性设计自动配置
 * 提供熔断器、限流器、重试、隔离舱壁的统一配置
 */
@AutoConfiguration
@EnableConfigurationProperties(Resilience4jProperties.class)
@Import(CircuitBreakerAutoConfiguration.class)
public class Resilience4jAutoConfiguration {

    private final Resilience4jProperties properties;

    public Resilience4jAutoConfiguration(Resilience4jProperties properties) {
        this.properties = properties;
    }

    /**
     * 熔断器配置
     * - 滑动窗口统计
     * - 失败率阈值触发熔断
     * - 半开状态探测恢复
     */
    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerConfig circuitBreakerConfig() {
        // 将字符串列表转换为 Class 数组
        Class<? extends Throwable>[] exceptionClasses = properties.getCircuitBreaker().getRecordExceptions().stream()
            .map(this::loadExceptionClass)
            .filter(java.util.Objects::nonNull)
            .toArray(Class[]::new);

        return CircuitBreakerConfig.custom()
            .failureRateThreshold(properties.getCircuitBreaker().getFailureRateThreshold())
            .slowCallRateThreshold(properties.getCircuitBreaker().getSlowCallRateThreshold())
            .slowCallDurationThreshold(Duration.ofMillis(properties.getCircuitBreaker().getSlowCallDurationThresholdMs()))
            .slidingWindowType(properties.getCircuitBreaker().isSlidingWindowCountBased() 
                ? CircuitBreakerConfig.SlidingWindowType.COUNT_BASED 
                : CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
            .slidingWindowSize(properties.getCircuitBreaker().getSlidingWindowSize())
            .minimumNumberOfCalls(properties.getCircuitBreaker().getMinimumNumberOfCalls())
            .permittedNumberOfCallsInHalfOpenState(properties.getCircuitBreaker().getPermittedCallsInHalfOpen())
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .waitDurationInOpenState(Duration.ofMillis(properties.getCircuitBreaker().getWaitDurationInOpenStateMs()))
            .recordExceptions(exceptionClasses)
            .build();
    }

    /**
     * 限流器配置
     * - 令牌桶算法
     * - 动态刷新限流阈值
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimiterConfig rateLimiterConfig() {
        return RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofMillis(properties.getRateLimiter().getLimitRefreshPeriodMs()))
            .limitForPeriod(properties.getRateLimiter().getLimitForPeriod())
            .timeoutDuration(Duration.ofMillis(properties.getRateLimiter().getTimeoutDurationMs()))
            .build();
    }

    /**
     * 重试配置
     * - 固定/指数退避
     * - 可配置重试异常
     * - 最大重试次数
     */
    @Bean
    @ConditionalOnMissingBean
    public RetryConfig retryConfig() {
        Class<? extends Throwable>[] retryExceptions = properties.getRetry().getRetryExceptions().stream()
            .map(this::loadExceptionClass)
            .filter(java.util.Objects::nonNull)
            .toArray(Class[]::new);

        Class<? extends Throwable>[] ignoreExceptions = properties.getRetry().getIgnoreExceptions().stream()
            .map(this::loadExceptionClass)
            .filter(java.util.Objects::nonNull)
            .toArray(Class[]::new);

        RetryConfig.Builder builder = RetryConfig.custom()
            .maxAttempts(properties.getRetry().getMaxAttempts())
            .waitDuration(Duration.ofMillis(properties.getRetry().getWaitDurationMs()))
            .retryExceptions(retryExceptions)
            .ignoreExceptions(ignoreExceptions);

        return builder.build();
    }

    /**
     * 隔离舱壁配置
     * - 信号量隔离（适合虚拟线程）
     * - 并发调用限制
     * - 等待队列
     */
    @Bean
    @ConditionalOnMissingBean
    public BulkheadConfig bulkheadConfig() {
        return BulkheadConfig.custom()
            .maxConcurrentCalls(properties.getBulkhead().getMaxConcurrentCalls())
            .maxWaitDuration(Duration.ofMillis(properties.getBulkhead().getMaxWaitDurationMs()))
            .build();
    }

    /**
     * 熔断器注册表（用于动态管理）
     */
    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry circuitBreakerRegistry(CircuitBreakerConfig circuitBreakerConfig) {
        return CircuitBreakerRegistry.of(circuitBreakerConfig);
    }

    /**
     * 将异常类名字符串加载为 Class
     */
    @SuppressWarnings("unchecked")
    private Class<? extends Throwable> loadExceptionClass(String className) {
        try {
            return (Class<? extends Throwable>) Class.forName(className);
        } catch (ClassNotFoundException e) {
            // 忽略找不到的异常类
            return null;
        }
    }
}