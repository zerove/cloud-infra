package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.MonitoringSummaryView;
import com.cloudinfra.system.domain.NotificationDeliveryStats;
import com.cloudinfra.system.domain.PlatformHealthSnapshot;
import com.cloudinfra.system.domain.StorageUsageView;
import com.cloudinfra.system.domain.VirtualThreadStatsView;
import com.cloudinfra.system.mapper.PlatformMetricsMapper;
import com.cloudinfra.system.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;


/**
 * 业务监控服务 - 实现平台指标的实时聚合分析
 */
@Slf4j
@Service
public class MonitoringService {

    /**
     * DB 派生指标的 TTL (#18)。
     *
     * <p>快照端点 ({@code /system/monitoring/snapshot}) 由面板轮询, 而三个聚合
     * —— {@code sys_user} COUNT、{@code sys_storage_quota} SUM、{@code sys_notification_log}
     * GROUP BY —— 都是全表扫描。此前每次调用都重打一遍, 面板一刷新就是三次全表扫描。
     * 30s 内复用同一份结果, 把重复轮询合并为一次查询。
     *
     * <p>线程指标**不缓存** —— 它必须是调用瞬间的真实值。
     */
    private static final long METRICS_TTL_NANOS = Duration.ofSeconds(30).toNanos();

    private final SysUserMapper userMapper;
    private final PlatformMetricsMapper metricsMapper;
    private final LongSupplier nanoClock;

    private volatile CacheEntry cacheEntry;

    @Autowired
    public MonitoringService(SysUserMapper userMapper, PlatformMetricsMapper metricsMapper) {
        this(userMapper, metricsMapper, System::nanoTime);
    }

    /** 测试用: 注入可控时钟以验证 TTL 边界。 */
    MonitoringService(SysUserMapper userMapper, PlatformMetricsMapper metricsMapper, LongSupplier nanoClock) {
        this.userMapper = userMapper;
        this.metricsMapper = metricsMapper;
        this.nanoClock = nanoClock;
    }

    /**
     * 生成平台实时健康快照
     */
    public PlatformHealthSnapshot getHealthSnapshot() {
        DbMetrics db = dbMetrics();

        ThreadStats threads = currentThreadStats();

        return new PlatformHealthSnapshot(
            db.totalUsers(),
            0L,
            db.storageUtilization(),
            db.notificationSuccessRate(),
            threads.activeVirtual(),
            threads.blockedVirtual(),
            db.channelDistribution()
        );
    }

    /**
     * 存储配额使用情况(字节级使用/总量 + 利用率)。
     *
     * <p>与快照端点共用同一份 TTL 缓存的 {@link DbMetrics}, 查询侧轮询不会放大全表扫描。
     */
    public StorageUsageView getStorageUsage() {
        DbMetrics db = dbMetrics();
        double utilization = db.storageTotalBytes() <= 0
            ? 0.0
            : Math.min(1.0, (double) db.storageUsedBytes() / db.storageTotalBytes());
        return new StorageUsageView(db.storageUsedBytes(), db.storageTotalBytes(), utilization, utilization * 100.0);
    }

    /**
     * 通知投递成功率与各渠道投递量分布。
     *
     * <p>同样复用 TTL 缓存的 {@link DbMetrics}, 语义与快照中的通知指标一致。
     */
    public NotificationDeliveryStats getNotificationDeliveryStats() {
        DbMetrics db = dbMetrics();
        return new NotificationDeliveryStats(db.notificationSuccessRate(), db.channelDistribution());
    }

    /**
     * 虚拟线程运行时观测（调用瞬间采样，不走 TTL 缓存）。
     */
    public VirtualThreadStatsView getVirtualThreadStats() {
        ThreadStats threads = currentThreadStats();
        return new VirtualThreadStatsView(threads.activeVirtual(), threads.blockedVirtual());
    }

    /**
     * 轻量监控摘要：DB 派生指标复用 TTL 缓存，线程为实时采样。
     */
    public MonitoringSummaryView getSummary() {
        DbMetrics db = dbMetrics();
        ThreadStats threads = currentThreadStats();
        return new MonitoringSummaryView(
            db.totalUsers(),
            db.storageUtilization(),
            db.storageUtilization() * 100.0,
            db.notificationSuccessRate(),
            threads.activeVirtual(),
            threads.blockedVirtual()
        );
    }

    /**
     * 读取 DB 派生指标, 命中 TTL 则直接复用。
     *
     * <p>无锁: 并发未命中最多触发几次重复查询, 不影响正确性; 而 {@code synchronized}
     * 会让虚拟线程被钉住 (见 AGENTS.md 技术栈陷阱)。整条缓存条目放在单个 volatile
     * 引用里, 读到的 metrics 与时间戳必然成对。
     */
    private DbMetrics dbMetrics() {
        long now = nanoClock.getAsLong();
        CacheEntry entry = cacheEntry;
        if (entry != null && now - entry.atNanos() < METRICS_TTL_NANOS) {
            return entry.metrics();
        }
        DbMetrics fresh = loadMetrics();
        cacheEntry = new CacheEntry(fresh, now);
        return fresh;
    }

    private DbMetrics loadMetrics() {
        long totalUsers = userMapper.selectCount(null);
        NotificationStats notifications = calculateNotificationStats();
        Map<String, Long> distribution =
            Collections.unmodifiableMap(new LinkedHashMap<>(notifications.distribution()));
        PlatformMetricsMapper.StorageUsage usage = metricsMapper.selectStorageUsage();
        long usedBytes = usage == null ? 0L : usage.used();
        long totalBytes = usage == null || usage.total() <= 0 ? 0L : usage.total();
        return new DbMetrics(totalUsers, usedBytes, totalBytes, notifications.successRate(), distribution);
    }

    private NotificationStats calculateNotificationStats() {
        List<PlatformMetricsMapper.ChannelDelivery> stats = metricsMapper.selectChannelDeliveryStats();
        if (stats == null || stats.isEmpty()) {
            return new NotificationStats(1.0, Map.of());
        }
        long total = 0;
        long success = 0;
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (PlatformMetricsMapper.ChannelDelivery row : stats) {
            total += row.total();
            success += row.success();
            distribution.put(row.channelCode(), row.total());
        }
        return new NotificationStats(total == 0 ? 1.0 : (double) success / total, distribution);
    }

    /**
     * 虚拟线程现状: 活跃数 + 处于 BLOCKED 的数量。
     *
     * <p>此前 pinnedThreadCount 写死为 0, 使 {@code HealthWatcher} 的并发告警成为死代码。
     * 这里以「阻塞中的虚拟线程」作为钉住风险的可观测代理 —— 在 synchronized 等监视器上
     * 等待的虚拟线程正是钉住问题的典型表现。采集不到时返回 0, 不伪造数字。
     */
    private ThreadStats currentThreadStats() {
        try {
            int active = 0;
            int blocked = 0;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (!thread.isVirtual()) {
                    continue;
                }
                active++;
                if (thread.getState() == Thread.State.BLOCKED) {
                    blocked++;
                }
            }
            return new ThreadStats(active, blocked);
        } catch (RuntimeException e) {
            log.warn("采集虚拟线程指标失败, 本轮按 0 计", e);
            return new ThreadStats(0, 0);
        }
    }

    private record CacheEntry(DbMetrics metrics, long atNanos) {
    }

    private record DbMetrics(long totalUsers, long storageUsedBytes, long storageTotalBytes, double notificationSuccessRate,
                             Map<String, Long> channelDistribution) {

        double storageUtilization() {
            if (storageTotalBytes <= 0) {
                return 0.0;
            }
            return Math.min(1.0, (double) storageUsedBytes / storageTotalBytes);
        }
    }

    private record ThreadStats(int activeVirtual, int blockedVirtual) {
    }

    private record NotificationStats(double successRate, Map<String, Long> distribution) {
    }
}
