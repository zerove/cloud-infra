package com.cloudinfra.system.domain;

import java.util.Map;

/**
 * 通知投递指标视图。
 *
 * <p>全局投递成功率 + 各渠道投递量分布（channelCode → total）。
 */
public record NotificationDeliveryStats(double successRate, Map<String, Long> channelDistribution) {
}
