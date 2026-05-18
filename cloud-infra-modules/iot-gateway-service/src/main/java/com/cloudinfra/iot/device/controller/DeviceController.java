package com.cloudinfra.iot.device.controller;

import com.cloudinfra.iot.device.model.Device;
import com.cloudinfra.iot.device.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/iot/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @GetMapping("/{deviceId}")
    public Object getDeviceStatus(@PathVariable String deviceId) {
        return deviceService.getDeviceShadow(deviceId);
    }

    @PostMapping("/{deviceId}/report")
    public String reportState(@PathVariable String deviceId, @RequestBody Map<String, Object> state) {
        log.info("Device {} reported state: {}", deviceId, state);
        deviceService.updateDeviceShadow(deviceId, state);
        return "Report processed";
    }

    @PostMapping("/{deviceId}/command")
    public String sendCommand(@PathVariable String deviceId, @RequestBody Map<String, Object> command) {
        log.info("Sending command to device {}: {}", deviceId, command);
        return "Command sent";
    }
}
