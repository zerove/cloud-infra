package com.cloudinfra.notification.service;

import com.cloudinfra.common.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class NotificationQuotaServiceTest {

    private static final String DAILY_KEY_USER_1 = "notification:quota:daily:1";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private NotificationQuotaService quotaService;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        quotaService = new NotificationQuotaService(redisTemplate);
    }

    @Test
    void checkQuota_firstIncrement_setsDailyExpiry() {
        given(valueOps.increment(DAILY_KEY_USER_1)).willReturn(1L);

        quotaService.checkQuota(1L);

        then(redisTemplate).should().expire(DAILY_KEY_USER_1, 24, TimeUnit.HOURS);
    }

    @Test
    void checkQuota_atLimit_isAllowed() {
        given(valueOps.increment(DAILY_KEY_USER_1)).willReturn(1000L);

        quotaService.checkQuota(1L);

        then(valueOps).should(never()).decrement(DAILY_KEY_USER_1);
        then(redisTemplate).should(never()).expire(DAILY_KEY_USER_1, 24, TimeUnit.HOURS);
    }

    @Test
    void checkQuota_overLimit_rollsBackIncrementAndThrows() {
        given(valueOps.increment(DAILY_KEY_USER_1)).willReturn(1001L);

        assertThatThrownBy(() -> quotaService.checkQuota(1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("资源配额已用尽");

        then(valueOps).should().decrement(DAILY_KEY_USER_1);
    }

    @Test
    void refundQuota_decrementsSameDailyKey() {
        quotaService.refundQuota(1L);

        then(valueOps).should().decrement(DAILY_KEY_USER_1);
    }
}
