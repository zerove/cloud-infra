package com.cloudinfra.system.service;

import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.entity.SysOperLog;
import com.cloudinfra.system.domain.UnifiedAuditRecord;
import com.cloudinfra.system.mapper.SysOperLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 统一审计聚合服务 - 实现全链路行为时间线追踪
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditAggregatorService {

    private final SysOperLogMapper operLogMapper;
    private final NotificationApi notificationApi;

    /**
     * 获取指定用户的统一行为时间线
     * @param userId 用户ID
     * @return 综合排序后的审计记录列表
     */
    public List<UnifiedAuditRecord> getUnifiedTimeline(Long userId) {
        // 使用虚拟线程并发拉取不同源的日志
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            
            // 1. 异步拉取系统操作日志 (本地 DB)
            CompletableFuture<List<UnifiedAuditRecord>> operLogsFuture = CompletableFuture.supplyAsync(() -> {
                return operLogMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysOperLog>()
                        .eq(SysOperLog::getUserId, userId)
                        .orderByDesc(SysOperLog::getCreateTime)
                ).stream().map(log -> new UnifiedAuditRecord(
                    log.getCreateTime(),
                    userId.toString(),
                    log.getTitle(),
                    log.getOperUrl(),
                    "SUCCESS", 
                    log.getOperName(),
                    "SYSTEM"
                )).collect(Collectors.toList());
            }, executor);

            // 2. 异步拉取通知发送日志 (远程 API)
            CompletableFuture<List<UnifiedAuditRecord>> notifyLogsFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    Result<List<NotificationLogDTO>> result = notificationApi.getLogs(userId);
                    if (result == null || result.getData() == null) return Collections.emptyList();
                    
                    return result.getData().stream().map(log -> new UnifiedAuditRecord(
                        log.getCreateTime(),
                        userId.toString(),
                        "发送通知 [" + log.getChannelCode() + "]",
                        log.getNotificationId().toString(),
                        log.getStatus(),
                        log.getErrorMessage(),
                        "NOTIFICATION"
                    )).collect(Collectors.toList());
                } catch (Exception e) {
                    log.error("拉取通知审计日志失败: {}", e.getMessage());
                    return Collections.emptyList();
                }
            }, executor);

            // 3. 使用 thenCombine 避免阻塞 join，异步组合两个结果
            return CompletableFuture.allOf(operLogsFuture, notifyLogsFuture)
                .thenApply(v -> Stream.concat(
                    operLogsFuture.join().stream(), // allOf 完成后 join 不会阻塞
                    notifyLogsFuture.join().stream()
                ).sorted(Comparator.comparing(UnifiedAuditRecord::timestamp).reversed())
                .collect(Collectors.toList()))
                .join(); // 最终 join 在虚拟线程中可接受
        } catch (Exception e) {
            log.error("审计聚合异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
