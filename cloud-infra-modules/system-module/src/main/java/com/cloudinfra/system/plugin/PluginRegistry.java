package com.cloudinfra.system.plugin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 插件服务注册表 - 存储所有激活插件提供的功能
 */
@Slf4j
@Component
public class PluginRegistry {
    private final Map<String, Object> services = new ConcurrentHashMap<>();

    public void register(String name, Object service) {
        services.put(name, service);
    }

    public void unregister(String name) {
        services.remove(name);
    }

    public Object getService(String name) {
        return services.get(name);
    }

    public Map<String, Object> getAllServices() {
        return services;
    }
}