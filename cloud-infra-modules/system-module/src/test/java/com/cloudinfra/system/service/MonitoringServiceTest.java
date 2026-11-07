package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.NotificationDeliveryStats;
import com.cloudinfra.system.domain.PlatformHealthSnapshot;
import com.cloudinfra.system.domain.StorageUsageView;
import com.cloudinfra.system.mapper.PlatformMetricsMapper;
import com.cloudinfra.system.mapper.SysUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 契约 (#18): 全表聚合指标必须在 TTL 内被缓存。
 *
 * <p>快照端点由面板轮询, 而三个聚合都是全表扫描; 没有缓存时一次刷新就是三次全表扫描。
 * 本测试用可控时钟验证「TTL 内只查一次 / 超时后重新查」。
 */
class MonitoringServiceTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final PlatformMetricsMapper metricsMapper = mock(PlatformMetricsMapper.class);
    private final AtomicLong nanoTime = new AtomicLong(0);

    private MonitoringService newService() {
        given(userMapper.selectCount(any())).willReturn(42L);
        given(metricsMapper.selectStorageUsage())
            .willReturn(new PlatformMetricsMapper.StorageUsage(50, 100));
        given(metricsMapper.selectChannelDeliveryStats())
            .willReturn(List.of(new PlatformMetricsMapper.ChannelDelivery("EMAIL", 10, 8)));
        return new MonitoringService(userMapper, metricsMapper, nanoTime::get);
    }

    @Test
    @DisplayName("TTL 内重复调用只查库一次")
    void repeatedCallsWithinTtlHitDatabaseOnce() {
        MonitoringService service = newService();

        PlatformHealthSnapshot first = service.getHealthSnapshot();
        nanoTime.addAndGet(TTL.minusSeconds(1).toNanos());
        PlatformHealthSnapshot second = service.getHealthSnapshot();

        verify(userMapper, times(1)).selectCount(any());
        verify(metricsMapper, times(1)).selectStorageUsage();
        verify(metricsMapper, times(1)).selectChannelDeliveryStats();
        assertThat(first.totalUsers()).isEqualTo(42L);
        assertThat(second.totalUsers()).isEqualTo(42L);
    }

    @Test
    @DisplayName("超过 TTL 后重新查库")
    void callsAfterTtlRefresh() {
        MonitoringService service = newService();

        service.getHealthSnapshot();
        nanoTime.addAndGet(TTL.plusSeconds(1).toNanos());
        service.getHealthSnapshot();

        verify(userMapper, times(2)).selectCount(any());
        verify(metricsMapper, times(2)).selectStorageUsage();
        verify(metricsMapper, times(2)).selectChannelDeliveryStats();
    }

    @Test
    @DisplayName("派生指标正确映射到快照")
    void snapshotReflectsAggregates() {
        MonitoringService service = newService();

        PlatformHealthSnapshot snapshot = service.getHealthSnapshot();

        assertThat(snapshot.totalUsers()).isEqualTo(42L);
        assertThat(snapshot.storageUtilization()).isEqualTo(0.5);
        assertThat(snapshot.notificationSuccessRate()).isEqualTo(0.8);
        assertThat(snapshot.channelDistribution()).containsEntry("EMAIL", 10L);
    }

    @Test
    @DisplayName("存储配额视图: 字节级使用/总量与利用率映射正确")
    void storageUsageMapsBytesAndUtilization() {
        MonitoringService service = newService();

        StorageUsageView view = service.getStorageUsage();

        assertThat(view.usedBytes()).isEqualTo(50);
        assertThat(view.totalBytes()).isEqualTo(100);
        assertThat(view.utilization()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("投递指标: 成功率与渠道分布映射正确")
    void deliveryStatsMapSuccessRateAndDistribution() {
        MonitoringService service = newService();

        NotificationDeliveryStats stats = service.getNotificationDeliveryStats();

        assertThat(stats.successRate()).isEqualTo(0.8);
        assertThat(stats.channelDistribution()).containsEntry("EMAIL", 10L);
    }

    @Test
    @DisplayName("存储/投递指标查询与快照共用同一份 TTL 缓存")
    void queriesShareTtlCache() {
        MonitoringService service = newService();

        service.getStorageUsage();
        nanoTime.addAndGet(TTL.minusSeconds(1).toNanos());
        service.getStorageUsage();

        verify(metricsMapper, times(1)).selectStorageUsage();
    }
}
