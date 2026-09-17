package com.cloudinfra.framework.mybatis.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Outbox 事件分发器
 * 定时轮询待投递事件，通过 Spring Cloud Stream 投递到消息队列
 * 保证至少一次投递，消费端通过幂等键去重
 *
 * <p>仅在 classpath 存在 {@link StreamBridge} 时注册,
 * 网关等排除 spring-cloud-stream 的应用不会因此启动失败。</p>
 */
@Service
@ConditionalOnClass(StreamBridge.class)
public class OutboxEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventDispatcher.class);

    private final OutboxEventRepository repository;
    private final StreamBridge streamBridge;
    private final ObjectMapper objectMapper;

    @Value("${cloud-infra.outbox.batch-size:100}")
    private int batchSize;

    @Value("${cloud-infra.outbox.cleanup-days:7}")
    private int cleanupDays;

    @Value("${cloud-infra.outbox.cleanup-batch:1000}")
    private int cleanupBatch;

    @Value("${cloud-infra.outbox.enabled:true}")
    private boolean enabled;

    public OutboxEventDispatcher(OutboxEventRepository repository,
                                 StreamBridge streamBridge,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.streamBridge = streamBridge;
        this.objectMapper = objectMapper;
    }

    /**
     * 定时投递：默认每 1 秒轮询一次。
     * 整轮投递在单个事务内完成：lockAndFindPending 的 SKIP LOCKED 行锁
     * 保证多实例抢占互不重叠的事件集；事务提交时行锁释放。
     */
    @Scheduled(fixedDelayString = "${cloud-infra.outbox.poll-interval-ms:1000}")
    @Transactional(rollbackFor = Exception.class)
    public void dispatchPendingEvents() {
        if (!enabled) {
            return;
        }

        List<OutboxEvent> pendingEvents = repository.lockAndFindPending(batchSize);
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("发现 {} 条待投递 Outbox 事件", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                dispatchEvent(event);
            } catch (Exception e) {
                log.error("Outbox 事件投递失败: id={}, type={}, error={}",
                    event.getId(), event.getEventType(), e.getMessage());
                repository.markFailed(event.getId(), e.getMessage());
            }
        }
    }

    /**
     * 定时清理已投递的旧事件
     */
    @Scheduled(cron = "${cloud-infra.outbox.cleanup-cron:0 0 3 * * ?}")
    public void cleanDispatchedEvents() {
        int cleaned = repository.cleanDispatched(cleanupDays, cleanupBatch);
        if (cleaned > 0) {
            log.info("清理 {} 条已投递的 Outbox 事件", cleaned);
        }
    }

    /**
     * 投递单个事件
     */
    private void dispatchEvent(OutboxEvent event) {
        // 默认发布入口允许空租户，JSON null 也可能是有效负载。
        // 保留信封字段，不使用拒绝空值的 Map.of。
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("eventId", event.getId());
        message.put("eventType", event.getEventType());
        message.put("aggregateType", event.getAggregateType());
        message.put("aggregateId", event.getAggregateId());
        message.put("tenantId", event.getTenantId());
        message.put("idempotencyKey", event.getIdempotencyKey());
        message.put("timestamp", System.currentTimeMillis());
        message.put("payload", deserializePayload(event.getPayload()));

        boolean sent = streamBridge.send(event.getBindingName(), message);
        if (sent) {
            repository.markDispatched(event.getId());
            log.debug("Outbox 事件投递成功: id={}, binding={}", event.getId(), event.getBindingName());
        } else {
            throw new IllegalStateException("StreamBridge send returned false for binding: " + event.getBindingName());
        }
    }

    /**
     * 反序列化事件负载
     */
    private Object deserializePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, Object.class);
        } catch (Exception e) {
            // 如果无法解析为 JSON，原样返回字符串
            return payload;
        }
    }

    /**
     * 异步投递（供测试或手动触发）
     */
    @Async
    public CompletableFuture<Integer> dispatchNow() {
        dispatchPendingEvents();
        return CompletableFuture.completedFuture(0);
    }
}
