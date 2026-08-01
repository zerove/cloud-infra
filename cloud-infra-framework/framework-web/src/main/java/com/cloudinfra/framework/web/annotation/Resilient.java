package com.cloudinfra.framework.web.annotation;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 组合弹性注解
 * 一次性应用熔断、限流、重试、隔离
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@CircuitBreaker(name = "#{@environment.getProperty('resilience4j.circuitbreaker.name:default')}")
@RateLimiter(name = "#{@environment.getProperty('resilience4j.ratelimiter.name:default')}")
@Retry(name = "#{@environment.getProperty('resilience4j.retry.name:default')}")
@Bulkhead(name = "#{@environment.getProperty('resilience4j.bulkhead.name:default')}")
public @interface Resilient {
    String name() default "default";
    
    String fallbackMethod() default "";
}