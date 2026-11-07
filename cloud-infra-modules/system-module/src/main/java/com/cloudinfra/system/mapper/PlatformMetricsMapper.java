package com.cloudinfra.system.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 平台级聚合指标查询 (只读)。
 *
 * <p>这些指标此前在 {@code MonitoringService} 里是写死的常量, 导致
 * {@code HealthWatcher} 的三条告警在任何情况下都不可能触发 —— 阈值再严也没用。
 * 此处提供真实聚合, 让告警重新具备可达性。
 *
 * <p>不映射实体: 都是聚合结果, 没有对应的 POJO。
 */
@Mapper
public interface PlatformMetricsMapper {

    /** 存储配额汇总。{@code used}/{@code total} 即利用率分子分母。 */
    @Select("""
            SELECT COALESCE(SUM(used_size_bytes), 0) AS used,
                   COALESCE(SUM(max_size_bytes), 0)  AS total
            FROM sys_storage_quota
            """)
    StorageUsage selectStorageUsage();

    /** 按通道统计通知投递结果, 供计算成功率与通道分布。 */
    @Select("""
            SELECT channel_code                AS channelCode,
                   COUNT(*)                     AS total,
                   COUNT(*) FILTER (WHERE status = 'SUCCESS') AS success
            FROM sys_notification_log
            GROUP BY channel_code
            """)
    List<ChannelDelivery> selectChannelDeliveryStats();

    record StorageUsage(long used, long total) {
    }

    record ChannelDelivery(String channelCode, long total, long success) {
    }
}