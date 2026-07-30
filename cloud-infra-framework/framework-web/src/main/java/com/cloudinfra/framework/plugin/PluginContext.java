package com.cloudinfra.framework.plugin;

/**
 * 插件上下文 - 插件与底座通信的桥梁
 */
public interface PluginContext {
    /**
     * 获取底座提供的核心服务
     */
    <T> T getService(Class<T> serviceClass);

    /**
     * 注册插件提供的服务能力
     */
    void registerService(String serviceName, Object serviceInstance);

    /**
     * 记录插件日志
     */
    void log(String message);
}