package com.cloudinfra.file.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 存储策略工厂 - 支持动态路由选择
 */
@Component
public class StorageFactory {

    private final Map<String, StorageStrategy> strategies;
    
    @Value("${file.storage.type:auto}")
    private String activeStrategy;

    public StorageFactory(Map<String, StorageStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * 根据文件特征动态获取存储策略
     * @param fileSize 文件大小 (字节)
     * @param fileType 文件类型 (MIME type)
     * @return 最佳存储策略
     */
    public StorageStrategy getStrategy(long fileSize, String fileType) {
        // 1. 如果配置了强制策略且非 auto，则直接返回
        if (!"auto".equalsIgnoreCase(activeStrategy)) {
            return getStrategy();
        }

        // 2. 动态路由逻辑
        // 小文件 (< 10MB) 路由至本地存储以获取最高 I/O 性能
        if (fileSize < 10 * 1024 * 1024) {
            return strategies.getOrDefault("localStorageStrategy", strategies.get("minioStorageStrategy"));
        }

        // 大文件路由至 Minio 保证高可用和可扩展性
        return strategies.getOrDefault("minioStorageStrategy", strategies.get("localStorageStrategy"));
    }

    /**
     * 获取当前默认激活的存储策略
     */
    public StorageStrategy getStrategy() {
        String strategyName = "auto".equalsIgnoreCase(activeStrategy) ? "localStorageStrategy" : activeStrategy + "StorageStrategy";
        StorageStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            return strategies.get("localStorageStrategy");
        }
        return strategy;
    }
}
