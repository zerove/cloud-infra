package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.PlatformHealthSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


/**
 * 平台健康守护者 - 实现指标监控与自动告警闭环
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthWatcher {

    private final MonitoringService monitoringService;
    private final ServiceTicketService serviceTicketService;

    // 告警阈值定义
    private static final double STORAGE_CRITICAL_THRESHOLD = 0.9;
    private static final double NOTIFICATION_FAILURE_THRESHOLD = 0.2; // 失败率 > 20% 触发
    private static final int PINNED_THREAD_THRESHOLD = 10;

    /**
     * 每 5 分钟执行一次健康检查
     */
    @Scheduled(fixedRate = 300000)
    public void watch() {
        log.info("执行平台健康检查...");
        PlatformHealthSnapshot snapshot = monitoringService.getHealthSnapshot();

        // 1. 存储空间告警 -> 触发存储维修工单
        if (snapshot.storageUtilization() > STORAGE_CRITICAL_THRESHOLD) {
            serviceTicketService.createIntelligentTicket(
                "存储资源危机", 
                "平台整体存储利用率已达 " + String.format("%.2f%%", snapshot.storageUtilization() * 100) + ", 请立即扩容。", 
                4, // 紧急
                null // 系统资源工单，无需关联具体资产
            );
        }

        // 2. 通知成功率告警 -> 触发通道修复工单
        if ((1.0 - snapshot.notificationSuccessRate()) > NOTIFICATION_FAILURE_THRESHOLD) {
            serviceTicketService.createIntelligentTicket(
                "触达通道异常", 
                "当前通知成功率仅为 " + String.format("%.2f%%", snapshot.notificationSuccessRate() * 100) + ", 请排查运营商通道。", 
                3, // 高
                null
            );
        }

        // 3. 虚拟线程风险告警 -> 触发性能优化工单
        if (snapshot.pinnedThreadCount() > PINNED_THREAD_THRESHOLD) {
            serviceTicketService.createIntelligentTicket(
                "并发性能风险", 
                "监测到 " + snapshot.pinnedThreadCount() + " 个虚拟线程被钉住，请排查同步块使用情况。", 
                2, // 中
                null
            );
        }
    }

}
