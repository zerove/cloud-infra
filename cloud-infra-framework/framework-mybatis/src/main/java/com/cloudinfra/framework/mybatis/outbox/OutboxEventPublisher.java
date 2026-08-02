package com.cloudinfra.framework.mybatis.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Outbox 事件发布器
 * 业务事务内调用，事务提交后自动写入 Outbox 表
 * 使用 TransactionSynchronization 确保事务边界正确
 */
@Service
public class OutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxEventPublisher(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 在事务内发布事件（推荐方式）
     * 事务提交时写入 Outbox 表，保证与业务数据一致
     */
    public void publishTransactional(String bindingName,
                                     String aggregateType,
                                     String aggregateId,
                                     String eventType,
                                     Object payload) {
        publishTransactional(bindingName, aggregateType, aggregateId, eventType, payload, null, null);
    }

    /**
     * 在事务内发布事件（带租户和幂等键）
     */
    public void publishTransactional(String bindingName,
                                     String aggregateType,
                                     String aggregateId,
                                     String eventType,
                                     Object payload,
                                     String tenantId,
                                     String idempotencyKey) {
        String jsonPayload = serialize(payload);
        String idempotencyKeyFinal = idempotencyKey != null
            ? idempotencyKey
            : aggregateType + ":" + aggregateId + ":" + eventType;

        // 幂等检查：已存在则跳过
        if (repository.existsByIdempotencyKey(idempotencyKeyFinal)) {
            log.debug("Outbox 事件已存在，跳过: {}", idempotencyKeyFinal);
            return;
        }

        OutboxEvent event = OutboxEvent.builder()
            .aggregateType(aggregateType)
            .aggregateId(aggregateId)
            .eventType(eventType)
            .payload(jsonPayload)
            .bindingName(bindingName)
            .tenantId(tenantId)
            .idempotencyKey(idempotencyKeyFinal)
            .build();

        // 如果在事务内，注册事务同步回调，事务提交时写入
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        repository.insert(event);
                        log.debug("Outbox 事件已写入（事务提交后）: {}", eventType);
                    } catch (Exception e) {
                        log.error("Outbox 事件写入失败: {}", eventType, e);
                    }
                }
            });
        } else {
            // 无事务上下文，直接写入
            repository.insert(event);
            log.debug("Outbox 事件已写入（无事务）: {}", eventType);
        }
    }

    /**
     * 序列化事件负载
     */
    private String serialize(Object payload) {
        if (payload == null) {
            return null;
        }
        try {
            if (payload instanceof String str) {
                return str;
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("事件负载序列化失败，使用 toString: {}", payload.getClass().getSimpleName(), e);
            return String.valueOf(payload);
        }
    }
}