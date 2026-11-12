package com.cloudinfra.system.service;

import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.entity.SysOperLog;
import com.cloudinfra.common.core.tenant.TenantContext;
import com.cloudinfra.system.domain.UnifiedAuditRecord;
import com.cloudinfra.system.mapper.SysOperLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一审计聚合服务 - 实现全链路行为时间线追踪
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditAggregatorService {

    private final SysOperLogMapper operLogMapper;
    private final NotificationApi notificationApi;

    /** 时间线单源上限。与下方通知侧的 pageSize*maxPages 保持对称。 */
    private static final int OPER_LOG_LIMIT = 5000;

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
                // issue 36 深化防御: supplyAsync 提交到 virtual thread executor, ThreadLocal 不继承,
                // 必须先捕获再 set 回 TenantContext, 使 @TenantFilter 的租户过滤真正生效.
                String tenantId = TenantContext.getTenantIdOrDefault();
                TenantContext.setTenantId(tenantId);
                try {
                    return operLogMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysOperLog>()
                            .eq(SysOperLog::getUserId, userId)
                            .orderByDesc(SysOperLog::getCreateTime)
                            .last("LIMIT " + OPER_LOG_LIMIT)
                    ).stream().map(log -> new UnifiedAuditRecord(
                        log.getCreateTime(),
                        userId.toString(),
                        log.getTitle(),
                        log.getOperUrl(),
                        "SUCCESS", 
                        log.getOperName(),
                        "SYSTEM"
                    )).collect(Collectors.toList());
                } finally {
                    TenantContext.clear();
                }
            }, executor);

            // 2. 异步拉取通知发送日志 (远程 API)
            CompletableFuture<List<UnifiedAuditRecord>> notifyLogsFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    // B3/S2: 用户端 /notification/logs 禁止跨用户；审计聚合走内部 HMAC 契约
                    // issue 36 深化防御: 在 virtual thread 内注入租户上下文, 经 FeignAuthInterceptor
                    // 透传 X-Tenant-Id, 由下游 TenantContextFilter (验签后) 装配.
                    String tenantId = TenantContext.getTenantIdOrDefault();
                    TenantContext.setTenantId(tenantId);
                    Result<List<NotificationLogDTO>> result = notificationApi.getInternalLogs(userId);
                    if (result == null || result.getData() == null) {
                        return Collections.emptyList();
                    }

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
                } finally {
                    TenantContext.clear();
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
