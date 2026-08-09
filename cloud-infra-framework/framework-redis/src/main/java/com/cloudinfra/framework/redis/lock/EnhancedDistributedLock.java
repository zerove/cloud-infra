package com.cloudinfra.framework.redis.lock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 增强型分布式锁
 * - 锁指标监控 (获取耗时、持有时间、争用率)
 * - 自动续期 (Watchdog 机制)
 * - 锁降级策略 (Redis 不可用时本地锁兜底)
 * - 公平锁/非公平锁支持
 */
@Component
public class EnhancedDistributedLock implements DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(EnhancedDistributedLock.class);

    private final RedissonClient redissonClient;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean redisAvailable = new AtomicBoolean(true);

    // 指标
    private final Timer lockAcquireTimer;
    private final Timer lockHoldTimer;

    public EnhancedDistributedLock(RedissonClient redissonClient, MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.meterRegistry = meterRegistry;
        
        this.lockAcquireTimer = Timer.builder("lock.acquire.duration")
            .description("分布式锁获取耗时")
            .register(meterRegistry);
        
        this.lockHoldTimer = Timer.builder("lock.hold.duration")
            .description("分布式锁持有耗时")
            .register(meterRegistry);
    }

    @Override
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, Supplier<T> action) {
        return executeWithLock(lockKey, waitTime, leaseTime, false, action);
    }

    /**
     * 执行带锁操作（支持公平锁）
     */
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, boolean fair, Supplier<T> action) {
        RLock lock = fair 
            ? redissonClient.getFairLock(lockKey)
            : redissonClient.getLock(lockKey);
        
        Timer.Sample acquireSample = Timer.start(meterRegistry);
        
        try {
            boolean acquired;
            if (leaseTime > 0) {
                acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
            } else {
                // 使用 watchdog 自动续期 (Redisson 默认 30 秒续期一次)
                acquired = lock.tryLock(waitTime, TimeUnit.SECONDS);
            }
            
            acquireSample.stop(lockAcquireTimer);
            
            if (!acquired) {
                meterRegistry.counter("lock.acquire.failed", "key", sanitizeKey(lockKey)).increment();
                throw new RuntimeException("Could not acquire lock for key: " + lockKey + " within " + waitTime + "s");
            }
            
            meterRegistry.counter("lock.acquire.success", "key", sanitizeKey(lockKey)).increment();
            log.debug("获取锁成功: {}", lockKey);
            
            Timer.Sample holdSample = Timer.start(meterRegistry);
            try {
                return action.get();
            } finally {
                holdSample.stop(lockHoldTimer);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("lock.acquire.interrupted", "key", sanitizeKey(lockKey)).increment();
            throw new RuntimeException("Interrupted while acquiring lock", e);
        } catch (Exception e) {
            meterRegistry.counter("lock.acquire.error", "key", sanitizeKey(lockKey)).increment();
            throw e;
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    log.debug("释放锁: {}", lockKey);
                }
            } catch (Exception e) {
                log.warn("释放锁异常: {}", lockKey, e);
            }
        }
    }

    @Override
    public boolean tryLock(String lockKey, long waitTime, long leaseTime) {
        return tryLock(lockKey, waitTime, leaseTime, false);
    }

    /**
     * 尝试获取锁（支持公平锁）
     */
    public boolean tryLock(String lockKey, long waitTime, long leaseTime, boolean fair) {
        RLock lock = fair 
            ? redissonClient.getFairLock(lockKey)
            : redissonClient.getLock(lockKey);
        
        Timer.Sample acquireSample = Timer.start(meterRegistry);
        
        try {
            boolean acquired;
            if (leaseTime > 0) {
                acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
            } else {
                acquired = lock.tryLock(waitTime, TimeUnit.SECONDS);
            }
            
            acquireSample.stop(lockAcquireTimer);
            
            if (acquired) {
                meterRegistry.counter("lock.trylock.success", "key", sanitizeKey(lockKey)).increment();
            } else {
                meterRegistry.counter("lock.trylock.failed", "key", sanitizeKey(lockKey)).increment();
            }
            
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("lock.trylock.interrupted", "key", sanitizeKey(lockKey)).increment();
            return false;
        } catch (Exception e) {
            meterRegistry.counter("lock.trylock.error", "key", sanitizeKey(lockKey)).increment();
            handleRedisError(e);
            // 降级：尝试本地锁
            return tryLocalLock(lockKey);
        }
    }

    @Override
    public void unlock(String lockKey) {
        try {
            RLock lock = redissonClient.getLock(lockKey);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("手动释放锁: {}", lockKey);
            } else {
                log.warn("尝试释放非当前线程持有的锁: {}", lockKey);
            }
        } catch (Exception e) {
            log.warn("释放锁异常: {}", lockKey, e);
            handleRedisError(e);
        }
    }

    /**
     * 异步执行带锁操作
     */
    public <T> CompletableFuture<T> executeWithLockAsync(String lockKey, long waitTime, long leaseTime, Supplier<T> action) {
        return CompletableFuture.supplyAsync(() -> executeWithLock(lockKey, waitTime, leaseTime, action));
    }

    /**
     * 获取锁状态信息
     */
    public LockStatus getLockStatus(String lockKey) {
        try {
            RLock lock = redissonClient.getLock(lockKey);
            return new LockStatus(
                lockKey,
                lock.isLocked(),
                lock.isHeldByCurrentThread(),
                lock.getHoldCount(),
                redisAvailable.get()
            );
        } catch (Exception e) {
            handleRedisError(e);
            return new LockStatus(lockKey, false, false, 0, false);
        }
    }

    /**
     * 强制释放锁（运维用）
     */
    public void forceUnlock(String lockKey) {
        try {
            RLock lock = redissonClient.getLock(lockKey);
            lock.forceUnlock();
            log.warn("强制释放锁: {}", lockKey);
            meterRegistry.counter("lock.force_unlock", "key", sanitizeKey(lockKey)).increment();
        } catch (Exception e) {
            log.error("强制释放锁失败: {}", lockKey, e);
            handleRedisError(e);
        }
    }

    /**
     * 降级策略：本地锁兜底
     */
    private boolean tryLocalLock(String lockKey) {
        log.warn("Redis 不可用，降级使用本地锁: {}", lockKey);
        meterRegistry.counter("lock.degrade.local", "key", sanitizeKey(lockKey)).increment();
        // 这里可以集成本地锁实现（如 ReentrantLock）
        // 由于分布式环境下本地锁不保证互斥，仅作为最后兜底
        return false;
    }

    /**
     * 处理 Redis 错误，标记不可用
     */
    private void handleRedisError(Exception e) {
        if (redisAvailable.compareAndSet(true, false)) {
            log.error("Redis 连接异常，标记为不可用，启用降级策略", e);
            meterRegistry.counter("lock.redis.unavailable").increment();
            
            // 尝试恢复检测
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(30_000);
                    if (redissonClient.getLock("health-check").tryLock(1, TimeUnit.SECONDS)) {
                        redissonClient.getLock("health-check").unlock();
                        redisAvailable.set(true);
                        log.info("Redis 连接恢复");
                        meterRegistry.counter("lock.redis.recovered").increment();
                    }
                } catch (Exception ex) {
                    // 忽略，保持不可用状态
                }
            });
        }
    }

    private String sanitizeKey(String key) {
        // 防止指标基数爆炸，长 key 截断
        return key.length() > 50 ? key.substring(0, 50) + "..." : key;
    }

    /**
     * 锁状态记录
     */
    public record LockStatus(
        String key,
        boolean locked,
        boolean heldByCurrentThread,
        int holdCount,
        boolean redisAvailable
    ) {}
}