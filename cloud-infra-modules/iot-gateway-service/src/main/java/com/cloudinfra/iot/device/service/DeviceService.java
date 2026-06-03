package com.cloudinfra.iot.device.service;

import com.cloudinfra.iot.device.domain.DeviceShadow;
import com.cloudinfra.iot.device.infrastructure.DeviceShadowStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DeviceService {
    private final DeviceShadowStore shadowStore;

    public void updateDeviceShadow(String deviceId, Map<String, Object> newState) {
        DeviceShadow currentShadow = shadowStore.load(deviceId)
            .orElseGet(() -> DeviceShadow.builder()
                .deviceId(deviceId)
                .state(Map.of())
                .version(0L)
                .build());

        if (currentShadow.hasStateChanged(newState)) {
            DeviceShadow updatedShadow = currentShadow.updateState(newState);
            shadowStore.save(updatedShadow);
        }
    }

    public Object getDeviceShadow(String deviceId) {
        return shadowStore.load(deviceId)
            .map(DeviceShadow::getState)
            .orElse(null);
    }
}
