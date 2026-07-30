package com.cloudinfra.framework.plugin;

import java.util.Map;

/**
 * 插件元数据
 */
public record PluginMetadata(
    String id,
    String name,
    String version,
    String description,
    Map<String, String> properties
) {}