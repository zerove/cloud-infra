package com.cloudinfra.notification.service;

import com.cloudinfra.common.core.exception.BusinessException;
import com.cloudinfra.common.core.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 通知频率配额服务
 * 基于 Redis 实现日发送量限制
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationQuotaService {

    private final StringRedisTemplate redisTemplate;

    private static final String QUOTA_KEY_PREFIX = "notification:quota:daily:";
    private static final long DEFAULT_DAILY_LIMIT = 1000L; // 默认每日发送上限 1000 条

    /**
     * 校验发送频率是否超限
     * @param userId 用户ID
     */
    public void checkQuota(Long userId) {
        String key = QUOTA_KEY_PREFIX + userId;
        
        // 1. 原子递增计数
        Long currentCount = redisTemplate.opsForValue().increment(key);
        
        // 2. 首次计数设置过期时间 (24小时)
        if (currentCount != null && currentCount == 1L) {
            redisTemplate.expire(key, 24, TimeUnit.HOURS);
        }

        // 3. 校验是否超限
        if (currentCount != null && currentCount > DEFAULT_DAILY_LIMIT) {
            // 如果超限，将计数减回去 (避免误扣)
            redisTemplate.opsForValue().decrement(key);
            throw new BusinessException(ErrorCode.RESOURCE_EXHAUSTED, 
                "当日通知发送额度已用完 (上限: " + DEFAULT_DAILY_LIMIT + ")");
        }
    }
}