package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.framework.plugin.IServicePlugin;
import com.cloudinfra.system.plugin.PluginManager;
import com.cloudinfra.system.plugin.PluginRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 插件管理控制器
 */
@RestController
@RequestMapping("/system/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginManager pluginManager;
    private final PluginRegistry pluginRegistry;

    /**
     * 查看当前所有激活的插件
     */
    @GetMapping("/active")
    public Map<String, IServicePlugin> getActivePlugins() {
        return pluginManager.getActivePlugins();
    }

    /**
     * 查看所有已注册的服务能力
     */
    @GetMapping("/services")
    public Map<String, Object> getRegisteredServices() {
        return pluginRegistry.getAllServices();
    }

    /**
     * 手动卸载插件
     */
    @AuditLog(module = "PLUGIN", action = "UNLOAD")
    @DeleteMapping("/{pluginId}")
    public Result<Void> unload(@PathVariable String pluginId) {
        pluginManager.unloadPlugin(pluginId);
        return Result.success();
    }
}