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

    /**
     * **按实际存放位置** 解析策略 —— 读取与删除必须用这个，而不是 {@link #getStrategy()}。
     *
     * <p><b>为什么需要（2026-10-06 实测缺陷）</b>:
     * {@link FileServiceImpl} 的写路径（upload/update）用
     * {@link #getStrategy(long, String)} 按大小路由，而读路径
     * （download/downloadVersion/delete）用的是无参 {@link #getStrategy()}，
     * 后者在 {@code auto} 模式下恒返回 local。于是:
     * <ul>
     *   <li>上传 10MB 文件 → 走 MinIO，元数据记下裸对象名</li>
     *   <li>下载同一文件 → 走本地，{@code Paths.get(裸对象名)} 解析成相对路径</li>
     *   <li>结果: <b>“文件不存在: xxx.txt”</b>，HTTP 403</li>
     * </ul>
     * 即所有 ≥10MB 的文件<b>永远无法下载</b>，与桶名是否配对无关。
     *
     * <p><b>为什么不用“按大小重算”</b>: 那只是把不对称换成另一种错。
     * 阈值（10MB）或策略配置一旦变更，已入库的文件就会指向错误的存储后；
     * 调阈值也会让旧文件失效。本方法改为<b>探测</b>:
     * 依次问各策略“文件在你那儿吗”，命中即返回。
     *
     * <p><b>为何探测是可靠的</b>: {@code exists} 对不存在的对象只返回
     * {@code false}，不会抛异常也不会误判 —— 它是纯粹的读操作。
     * 唯一代价是多一次存储探测（本地是 stat，MinIO 是 HEAD 请求），
     * 相对文件传输开销可忽略。
     *
     * <p><b>顺序说明</b>: 先问默认策略（绝大多数命中，零额外开销），
     * 再问其余策略，仅在默认未命中时才继续。
     *
     * @param storedPath 元数据中记录的路径或对象名
     * @return 能定位到该文件的策略；全部未命中时返回默认策略，
     *         由调用方的 {@code download} 抛出真实的“文件不存在”
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
}
