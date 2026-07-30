package com.cloudinfra.framework.plugin;

/**
 * 智能服务插件标准接口
 */
public interface IServicePlugin {
    /**
     * 获取插件元数据
     */
    PluginMetadata getMetadata();

    /**
     * 插件启动回调 - 注入底座上下文
     */
    void onEnable(PluginContext context);

    /**
     * 插件停止回调
     */
    void onDisable();
}