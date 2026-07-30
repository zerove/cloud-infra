package com.cloudinfra.framework.redis.lock;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁深层接口
 * 隐藏 Redisson 等具体实现的复杂性，提供高杠杆的锁操作
 */
public interface DistributedLock {
    /**
     * 执行锁保护的代码块 (Template Pattern)
     */
    <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, Supplier<T> action);
    
    /**
     * 简单的尝试加锁
     */
    boolean tryLock(String lockKey, long waitTime, long leaseTime);
    
    /**
     * 释放锁
     */
    void unlock(String lockKey);
}
