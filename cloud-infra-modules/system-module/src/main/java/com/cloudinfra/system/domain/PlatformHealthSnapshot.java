package com.cloudinfra.system.domain;

import java.util.Map;

/**
 * 平台健康快照
 * 聚合所有核心业务指标的实时状态
 */
public record PlatformHealthSnapshot(
    long totalUsers,
    long totalDevices,
    double storageUtilization, // 存储利用率 (0.0 - 1.0)
    double notificationSuccessRate, // 通知成功率 (0.0 - 1.0)
    long activeVirtualThreads,  // 当前活跃虚拟线程数
    int pinnedThreadCount,      // 钉住的线程数 (风险指标)
    Map<String, Long> channelDistribution // 各渠道发送量分布
) {}