package com.cloudinfra.iot.device.service;

import com.cloudinfra.iot.device.model.Device;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class DeviceService {
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String SHADOW_PREFIX = "device:shadow:";

    public void updateDeviceShadow(String deviceId, Object state) {
        redisTemplate.opsForValue().set(SHADOW_PREFIX + deviceId, state, Duration.ofHours(24));
    }

    public Object getDeviceShadow(String deviceId) {
        return redisTemplate.opsForValue().get(SHADOW_PREFIX + deviceId);
    }
}
