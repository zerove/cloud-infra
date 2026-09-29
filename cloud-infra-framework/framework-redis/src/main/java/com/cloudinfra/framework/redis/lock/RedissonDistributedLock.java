package com.cloudinfra.framework.redis.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class RedissonDistributedLock implements DistributedLock {

    private final RedissonClient redissonClient;

    public RedissonDistributedLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, Supplier<T> action) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS)) {
                try {
                    return action.get();
                } finally {
                    lock.unlock();
                }
            }
            throw new IllegalStateException("Could not acquire lock for key: " + lockKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring lock", e);
        }
    }

    @Override
    public boolean tryLock(String lockKey, long waitTime, long leaseTime) {
        try {
            return redissonClient.getLock(lockKey).tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void unlock(String lockKey) {
        redissonClient.getLock(lockKey).unlock();
    }
}
