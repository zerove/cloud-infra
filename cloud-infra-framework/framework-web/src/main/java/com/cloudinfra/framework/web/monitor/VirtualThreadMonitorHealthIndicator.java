package com.cloudinfra.framework.web.monitor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashMap;

@Component
public final class VirtualThreadMonitorHealthIndicator implements HealthIndicator {

    private final VirtualThreadPinnedMonitor pinnedMonitor;

    public VirtualThreadMonitorHealthIndicator(
            ObjectProvider<VirtualThreadPinnedMonitor> pinnedMonitorProvider) {
        this.pinnedMonitor = pinnedMonitorProvider.getIfAvailable();
    }

    @Override
    public Health health() {
        var builder = Health.up();

        // 当前线程是否为虚拟线程
        builder.withDetail("currentThreadIsVirtual", Thread.currentThread().isVirtual());

        // JVM 是否支持虚拟线程
        builder.withDetail("virtualThreadSupport", true);

        // 虚拟线程钉住监控状态
        boolean monitorRunning = pinnedMonitor != null && pinnedMonitor.isRunning();
        builder.withDetail("pinnedMonitorRunning", monitorRunning);
        builder.withDetail("pinnedMonitorAvailable", pinnedMonitor != null);

        // 平台线程统计（ThreadMXBean）
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        builder.withDetail("peakThreadCount", threadMXBean.getPeakThreadCount());
        builder.withDetail("totalStartedThreadCount", threadMXBean.getTotalStartedThreadCount());
        builder.withDetail("daemonThreadCount", threadMXBean.getDaemonThreadCount());
        builder.withDetail("liveThreadCount", threadMXBean.getThreadCount());

        // 健康细粒度: 按线程源拆分钉住计数 (AGENTS.md Q6 修复)
        if (pinnedMonitor != null && pinnedMonitor.isRunning()) {
            Map<String, Long> sourceCounts = new TreeMap<>();
            pinnedMonitor.getPinnedBySourceCounters().forEach((k, v) -> sourceCounts.put(k, v.sum()));

            List<Map<String, Object>> sources = sourceCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", e.getKey());
                    m.put("pinnedCount", e.getValue());
                    return m;
                })
                .toList();
            builder.withDetail("pinnedBySource", sources);
        }

        return builder.build();
    }
}