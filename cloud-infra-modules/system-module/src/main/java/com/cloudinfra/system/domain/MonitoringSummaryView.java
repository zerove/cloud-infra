package com.cloudinfra.system.domain;

/**
 * 监控摘要视图（轻量聚合，供高频轮询/卡片使用）。
 *
 * <p>DB 派生指标复用 TTL 缓存；线程指标为调用瞬间采样。
 */
public record MonitoringSummaryView(
    long totalUsers,
    double storageUtilization,
    double storageUtilizationPercent,
    double notificationSuccessRate,
    long activeVirtualThreads,
    int blockedVirtualThreads
) {
}

