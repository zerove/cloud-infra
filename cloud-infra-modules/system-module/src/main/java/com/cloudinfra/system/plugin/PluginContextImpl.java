package com.cloudinfra.system.plugin;

import com.cloudinfra.framework.plugin.PluginContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 插件上下文实现 - 连接插件与 Spring 容器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginContextImpl implements PluginContext {

    private final ApplicationContext applicationContext;
    private final PluginRegistry pluginRegistry;

    @Override
    public <T> T getService(Class<T> serviceClass) {
        log.debug("插件请求底座服务: {}", serviceClass.getName());
        return applicationContext.getBean(serviceClass);
    }

    @Override
    public void registerService(String serviceName, Object serviceInstance) {
        log.info("插件注册服务能力: {}", serviceName);
        pluginRegistry.register(serviceName, serviceInstance);
    }

    @Override
    public void log(String message) {
        log.info("[PluginLog] {}", message);
    }
}