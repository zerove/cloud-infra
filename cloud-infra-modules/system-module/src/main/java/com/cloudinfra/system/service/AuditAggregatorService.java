package com.cloudinfra.system.service;

import com.cloudinfra.api.notification.NotificationApi;
import com.cloudinfra.api.notification.dto.NotificationLogDTO;
import com.cloudinfra.common.core.domain.PageQuery;
import com.cloudinfra.common.core.domain.PageResult;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.entity.SysOperLog;
import com.cloudinfra.system.domain.UnifiedAuditRecord;
import com.cloudinfra.system.mapper.SysOperLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
                    mapOperLogStatus(log.getStatus()),
                    log.getOperName(),
                    "SYSTEM"
                )).collect(Collectors.toList());
            }, executor);

            // 2. 异步拉取通知发送日志 (远程 API)
            CompletableFuture<List<UnifiedAuditRecord>> notifyLogsFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    // 端点已分页：必须逐页取完, 否则审计聚合会静默丢数据
                    final int pageSize = 100;
                    final int maxPages = 50;
                    List<UnifiedAuditRecord> collected = new ArrayList<>();
                    for (int pageNum = 1; pageNum <= maxPages; pageNum++) {
                        PageQuery pageQuery = new PageQuery();
                        pageQuery.setPageNum(pageNum);
                        pageQuery.setPageSize(pageSize);
                        // S2-B3: 用户端 logs 禁止跨用户；审计聚合走内部 HMAC 契约端点
                        Result<PageResult<NotificationLogDTO>> result =
                            notificationApi.getInternalLogs(userId, pageQuery);
                        if (result == null || result.getData() == null || result.getData().getRecords() == null
                                || result.getData().getRecords().isEmpty()) {
                            break;
                        }
                        List<NotificationLogDTO> records = result.getData().getRecords();
                        records.forEach(log -> collected.add(new UnifiedAuditRecord(
                            log.getCreateTime(),
                            userId.toString(),
                            "发送通知 [" + log.getChannelCode() + "]",
                            log.getNotificationId().toString(),
                            log.getStatus(),
                            log.getErrorMessage(),
                            "NOTIFICATION"
                        )));
                        if (records.size() < pageSize) {
                            break;
                        }
                    }
                    return collected;
                } catch (Exception e) {
                    log.error("拉取通知审计日志失败", e);
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
            // 返回空集合会让"聚合整体失败"与"确实没有审计记录"在界面上无法区分;
            // 补全堆栈是最低要求 —— 仅打 getMessage() 会丢掉 Caused by 链, 排障时无从追起。
            log.error("审计聚合异常, 返回空结果", e);
            return Collections.emptyList();
        }
    }

    /**
     * SysOperLog.status 为 0/1 编码 (0=正常, 1=异常, 约定见 LogAspect)。
     * 修复前此处硬编码 "SUCCESS", 会把失败操作伪装成成功 —— 审计数据失真。
     */
    private static String mapOperLogStatus(Integer status) {
        return Integer.valueOf(1).equals(status) ? "FAIL" : "SUCCESS";
    }
}
