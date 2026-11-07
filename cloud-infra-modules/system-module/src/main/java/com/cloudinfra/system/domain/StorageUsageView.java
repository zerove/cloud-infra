package com.cloudinfra.system.domain;

/**
 * 存储配额使用情况视图。
 *
 * <p>字节级使用量 / 总量 + 利用率（0.0-1.0）。由 {@code MonitoringService} 聚合自
 * {@code sys_storage_quota}，经 TTL 缓存下发。
 */
public record StorageUsageView(long usedBytes, long totalBytes, long freeBytes, double utilization, double utilizationPercent) {
}


