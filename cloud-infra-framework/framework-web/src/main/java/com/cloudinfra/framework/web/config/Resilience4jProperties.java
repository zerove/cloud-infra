package com.cloudinfra.framework.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resilience4j 配置属性
 * 支持通过配置文件动态调整熔断、限流、重试、隔离参数
 */
@Component
@ConfigurationProperties(prefix = "resilience4j")
public class Resilience4jProperties {

    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private RateLimiter rateLimiter = new RateLimiter();
    private Retry retry = new Retry();
    private Bulkhead bulkhead = new Bulkhead();

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public void setRateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Bulkhead getBulkhead() {
        return bulkhead;
    }

    public void setBulkhead(Bulkhead bulkhead) {
        this.bulkhead = bulkhead;
    }

    // ===== 内部配置类 =====

    public static class CircuitBreaker {
        private int failureRateThreshold = 50;
        private int slowCallRateThreshold = 100;
        private long slowCallDurationThresholdMs = 2000;
        private boolean slidingWindowCountBased = true;
        private int slidingWindowSize = 100;
        private int minimumNumberOfCalls = 10;
        private int permittedCallsInHalfOpen = 10;
        private long waitDurationInOpenStateMs = 30000;
        private List<String> recordExceptions = List.of("java.io.IOException", "java.util.concurrent.TimeoutException", "org.springframework.web.client.ResourceAccessException");

        public int getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(int failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }

        public int getSlowCallRateThreshold() { return slowCallRateThreshold; }
        public void setSlowCallRateThreshold(int slowCallRateThreshold) { this.slowCallRateThreshold = slowCallRateThreshold; }

        public long getSlowCallDurationThresholdMs() { return slowCallDurationThresholdMs; }
        public void setSlowCallDurationThresholdMs(long slowCallDurationThresholdMs) { this.slowCallDurationThresholdMs = slowCallDurationThresholdMs; }

        public boolean isSlidingWindowCountBased() { return slidingWindowCountBased; }
        public void setSlidingWindowCountBased(boolean slidingWindowCountBased) { this.slidingWindowCountBased = slidingWindowCountBased; }

        public int getSlidingWindowSize() { return slidingWindowSize; }
        public void setSlidingWindowSize(int slidingWindowSize) { this.slidingWindowSize = slidingWindowSize; }

        public int getMinimumNumberOfCalls() { return minimumNumberOfCalls; }
        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) { this.minimumNumberOfCalls = minimumNumberOfCalls; }

        public int getPermittedCallsInHalfOpen() { return permittedCallsInHalfOpen; }
        public void setPermittedCallsInHalfOpen(int permittedCallsInHalfOpen) { this.permittedCallsInHalfOpen = permittedCallsInHalfOpen; }

        public long getWaitDurationInOpenStateMs() { return waitDurationInOpenStateMs; }
        public void setWaitDurationInOpenStateMs(long waitDurationInOpenStateMs) { this.waitDurationInOpenStateMs = waitDurationInOpenStateMs; }

        public List<String> getRecordExceptions() { return recordExceptions; }
        public void setRecordExceptions(List<String> recordExceptions) { this.recordExceptions = recordExceptions; }
    }

    public static class RateLimiter {
        private long limitRefreshPeriodMs = 1000;
        private int limitForPeriod = 100;
        private long timeoutDurationMs = 500;

        public long getLimitRefreshPeriodMs() { return limitRefreshPeriodMs; }
        public void setLimitRefreshPeriodMs(long limitRefreshPeriodMs) { this.limitRefreshPeriodMs = limitRefreshPeriodMs; }

        public int getLimitForPeriod() { return limitForPeriod; }
        public void setLimitForPeriod(int limitForPeriod) { this.limitForPeriod = limitForPeriod; }

        public long getTimeoutDurationMs() { return timeoutDurationMs; }
        public void setTimeoutDurationMs(long timeoutDurationMs) { this.timeoutDurationMs = timeoutDurationMs; }
    }

    public static class Retry {
        private int maxAttempts = 3;
        private long waitDurationMs = 500;
        private boolean exponentialBackoff = true;
        private double exponentialMultiplier = 2.0;
        private List<String> retryExceptions = List.of("java.io.IOException", "java.util.concurrent.TimeoutException");
        private List<String> ignoreExceptions = List.of("java.lang.IllegalArgumentException", "java.lang.IllegalStateException");

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public long getWaitDurationMs() { return waitDurationMs; }
        public void setWaitDurationMs(long waitDurationMs) { this.waitDurationMs = waitDurationMs; }

        public boolean isExponentialBackoff() { return exponentialBackoff; }
        public void setExponentialBackoff(boolean exponentialBackoff) { this.exponentialBackoff = exponentialBackoff; }

        public double getExponentialMultiplier() { return exponentialMultiplier; }
        public void setExponentialMultiplier(double exponentialMultiplier) { this.exponentialMultiplier = exponentialMultiplier; }

        public List<String> getRetryExceptions() { return retryExceptions; }
        public void setRetryExceptions(List<String> retryExceptions) { this.retryExceptions = retryExceptions; }

        public List<String> getIgnoreExceptions() { return ignoreExceptions; }
        public void setIgnoreExceptions(List<String> ignoreExceptions) { this.ignoreExceptions = ignoreExceptions; }
    }

    public static class Bulkhead {
        private int maxConcurrentCalls = 50;
        private long maxWaitDurationMs = 1000;

        public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
        public void setMaxConcurrentCalls(int maxConcurrentCalls) { this.maxConcurrentCalls = maxConcurrentCalls; }

        public long getMaxWaitDurationMs() { return maxWaitDurationMs; }
        public void setMaxWaitDurationMs(long maxWaitDurationMs) { this.maxWaitDurationMs = maxWaitDurationMs; }
    }
}