package com.cloudinfra.framework.web.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;

/**
 * 幂等响应存储 (M6-02)
 *
 * Redis 存储结构:
 * - {@code idempotency:{hash}:lock}     -> "1" (短 TTL, 锁)
 * - {@code idempotency:{hash}:status}   -> "IN_FLIGHT" | "COMPLETED"
 * - {@code idempotency:{hash}:response} -> JSON (IdempotencyEntry)
 *
 * 并发安全:
 * - SET NX EX 原子操作, 仅一个请求能获得锁 (IN_FLIGHT)
 * - 业务执行完后 SET status + response (覆盖 IN_FLIGHT)
 * - 二次调用检测到 status=COMPLETED 时读 response 直接返回
 *
 * Redis 不可用降级: 所有异常被捕获并 warn log, 返回 null (视为缓存未命中).
 */
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyStore(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 尝试获取幂等锁 (REPLAY 模式).
     * <p>
     * 返回值:
     * <ul>
     *   <li>true: 获得锁, 当前请求是首次调用, 可执行业务</li>
     *   <li>false: 未获得锁, 已有首次调用, 二次调用需等待/读取缓存</li>
     * </ul>
     */
    public boolean tryAcquire(String keyHash, int expireSeconds) {
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(
                IdempotencyEntry.lockKey(keyHash),
                "1",
                IdempotencyEntry.LOCK_TTL
            );
            if (Boolean.TRUE.equals(ok)) {
                // 标记 IN_FLIGHT (用于并发首次调用识别)
                redisTemplate.opsForValue().set(
                    IdempotencyEntry.statusKey(keyHash),
                    IdempotencyEntry.STATUS_IN_FLIGHT,
                    IdempotencyEntry.LOCK_TTL
                );
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("[IdempotencyStore] tryAcquire Redis 异常, 降级放行: {}", e.getMessage());
            return true;  // 降级: Redis 故障时放行
        }
    }

    /**
     * 读取已缓存的幂等响应.
     *
     * @return IdempotencyEntry (COMPLETED), null (未完成或已过期)
     */
    public IdempotencyEntry read(String keyHash) {
        try {
            Object status = redisTemplate.opsForValue().get(IdempotencyEntry.statusKey(keyHash));
            if (!IdempotencyEntry.STATUS_COMPLETED.equals(status)) {
                return null;
            }
            Object raw = redisTemplate.opsForValue().get(IdempotencyEntry.responseKey(keyHash));
            if (raw == null) {
                return null;
            }
            // RedisTemplate 反序列化为 LinkedHashMap (Jackson JSON)
            return objectMapper.convertValue(raw, IdempotencyEntry.class);
        } catch (Exception e) {
            log.warn("[IdempotencyStore] read Redis 异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 存储幂等响应 (REPLAY 模式, 业务执行成功后调用).
     */
    public void storeResponse(String keyHash, IdempotencyEntry entry, int expireSeconds) {
        try {
            redisTemplate.opsForValue().set(
                IdempotencyEntry.responseKey(keyHash),
                entry,
                Duration.ofSeconds(expireSeconds)
            );
            redisTemplate.opsForValue().set(
                IdempotencyEntry.statusKey(keyHash),
                IdempotencyEntry.STATUS_COMPLETED,
                Duration.ofSeconds(expireSeconds)
            );
        } catch (Exception e) {
            log.warn("[IdempotencyStore] storeResponse Redis 异常: {}", e.getMessage());
        }
    }

    /**
     * 检查是否处于 IN_FLIGHT 状态 (用于二次调用识别).
     */
    public boolean isInFlight(String keyHash) {
        try {
            Object status = redisTemplate.opsForValue().get(IdempotencyEntry.statusKey(keyHash));
            return IdempotencyEntry.STATUS_IN_FLIGHT.equals(status);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 释放锁 (业务执行失败时调用, 允许快速重试).
     */
    public void releaseLock(String keyHash) {
        try {
            redisTemplate.delete(IdempotencyEntry.lockKey(keyHash));
            redisTemplate.delete(IdempotencyEntry.statusKey(keyHash));
        } catch (Exception e) {
            log.warn("[IdempotencyStore] releaseLock Redis 异常: {}", e.getMessage());
        }
    }
}