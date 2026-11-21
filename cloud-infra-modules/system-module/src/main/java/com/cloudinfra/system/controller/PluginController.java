package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.framework.plugin.IServicePlugin;
import com.cloudinfra.system.plugin.PluginManager;
import com.cloudinfra.system.plugin.PluginRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 插件管理控制器
 *
 * <p><b>2026-11-09 修正</b>: 必须与 {@link PluginManager} 同条件。
 * 此前控制器无条件依赖管理器, 而管理器自 ADR-010 起是
 * {@code @ConditionalOnProperty(cloud-infra.plugin.enabled=true, matchIfMissing=true)}。
 * 2026-10-15 起 Nacos 配置把该开关改为默认 {@code false}（fail-closed 安全基线）,
 * 于是 system-module 启动即失败:
 * {@code Parameter 0 of constructor in PluginController required a bean of type
 * 'PluginManager' that could not be found}。禁用插件管理时接口整体不暴露（404）。</p>
 */
@RestController
@RequestMapping("/system/plugins")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cloud-infra.plugin.enabled", havingValue = "true", matchIfMissing = true)
public class PluginController {

    private final PluginManager pluginManager;
    private final PluginRegistry pluginRegistry;

    /**
     * 查看当前所有激活的插件
     */
    @PreAuthorize("@ss.hasPermi('system:plugin:list')")
    @GetMapping("/active")
    public Map<String, IServicePlugin> getActivePlugins() {
        return pluginManager.getActivePlugins();
    }

    /**
     * 查看所有已注册的服务能力
     */
    @PreAuthorize("@ss.hasPermi('system:plugin:list')")
    @GetMapping("/services")
    public Map<String, Object> getRegisteredServices() {
        return pluginRegistry.getAllServices();
    }

    /**
     * 手动卸载插件
     */
    @AuditLog(module = "PLUGIN", action = "UNLOAD")
    @PreAuthorize("@ss.hasPermi('system:plugin:unload')")
    @DeleteMapping("/{pluginId}")
    public Result<Void> unload(@PathVariable String pluginId) {
        pluginManager.unloadPlugin(pluginId);
        return Result.success();
    }
}