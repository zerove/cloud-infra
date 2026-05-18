package com.cloudinfra.iot.device.model;

import lombok.Data;
import java.util.Map;

@Data
public class Device {
    private String deviceId;
    private String name;
    private String category; // CAR, AIR_CONDITIONER, LIGHT, PC, etc.
    private String status;   // ONLINE, OFFLINE
    private Map<String, Object> properties; // 实时属性状态
}
