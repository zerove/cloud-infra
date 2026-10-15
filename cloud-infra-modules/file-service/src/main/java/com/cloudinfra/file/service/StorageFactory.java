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
     * 按"哪个后端真正持有该文件"来定位读取/删除用的策略。
     *
     * <p>不能直接用 {@link #getStrategy()}：它返回的是**当前默认**策略，而文件
     * 实际可能在另一个后端（例：默认 local，但该文件是按大小路由到 MinIO 存下的）。
     * 此时用默认策略删除会**静默无效** —— 既没删掉对象，调用方也只看到一个 false。
     *
     * <p>顺序：先问默认策略（绝大多数命中，零额外开销），未命中再逐个问其余策略。
     *
     * @param storedPath 元数据中记录的路径或对象名
     * @return 能定位到该文件的策略；全部未命中时返回默认策略，由调用方的
     *         {@code download} 抛出真实的"文件不存在"
     */
    public StorageStrategy resolveForRead(String storedPath) {
        if (storedPath == null || storedPath.isEmpty()) {
            return getStrategy();
        }
        StorageStrategy fallback = getStrategy();
        if (fallback.exists(storedPath)) {
            return fallback;
        }
        for (StorageStrategy strategy : strategies.values()) {
            if (strategy == fallback) {
                continue;
            }
            if (strategy.exists(storedPath)) {
                return strategy;
            }
        }
        return fallback;
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
