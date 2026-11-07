package com.cloudinfra.system.controller;

import com.cloudinfra.system.domain.MonitoringSummaryView;
import com.cloudinfra.system.domain.NotificationDeliveryStats;
import com.cloudinfra.system.domain.PlatformHealthSnapshot;
import com.cloudinfra.system.domain.StorageUsageView;
import com.cloudinfra.system.domain.VirtualThreadStatsView;
import com.cloudinfra.system.service.MonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/system/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitoringService monitoringService;

    /**
     * 获取平台实时健康快照
     */
    @PreAuthorize("@ss.hasPermi('system:monitoring:list')")
    @GetMapping("/snapshot")
    public PlatformHealthSnapshot getSnapshot() {
        return monitoringService.getHealthSnapshot();
    }

    /**
     * 获取存储配额使用情况（字节级使用/总量 + 利用率）
     */
    @PreAuthorize("@ss.hasPermi('system:monitoring:list')")
    @GetMapping("/storage")
    public StorageUsageView getStorageUsage() {
        return monitoringService.getStorageUsage();
    }

    /**
     * 获取通知投递成功率与各渠道投递量分布
     */
    @PreAuthorize("@ss.hasPermi('system:monitoring:list')")
    @GetMapping("/notifications")
    public NotificationDeliveryStats getNotificationDeliveryStats() {
        return monitoringService.getNotificationDeliveryStats();
    }

    /**
     * 获取虚拟线程运行时观测（调用瞬间采样，不走 TTL 缓存）
     */
    @PreAuthorize("@ss.hasPermi('system:monitoring:list')")
    @GetMapping("/threads")
    public VirtualThreadStatsView getVirtualThreadStats() {
        return monitoringService.getVirtualThreadStats();
    }

    /**
     * 获取轻量监控摘要（DB 指标复用 TTL 缓存，线程实时采样）
     */
    @PreAuthorize("@ss.hasPermi('system:monitoring:list')")
    @GetMapping("/summary")
    public MonitoringSummaryView getSummary() {
        return monitoringService.getSummary();
    }
}