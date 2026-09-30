package com.cloudinfra.framework.mybatis.outbox;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Outbox 事件记录
 * 业务事务内写入，由 Dispatcher 异步投递，保证至少一次投递
 */
@Getter
@Builder
public class OutboxEvent {

    /** 事件 ID */
    private final Long id;

    /** 聚合根类型（如 user, order） */
    private final String aggregateType;

    /** 聚合根 ID */
    private final String aggregateId;

    /** 事件类型（如 user.created, order.paid） */
    private final String eventType;

    /** 事件负载（JSON 序列化） */
    private final String payload;

    /** 目标绑定（Spring Cloud Stream binding name） */
    private final String bindingName;

    /** 状态: PENDING / DISPATCHED / FAILED */
    @Builder.Default
    private final String status = "PENDING";

    /** 投递尝试次数 */
    @Builder.Default
    private final Integer retryCount = 0;

    /** 创建时间 */
    @Builder.Default
    private final LocalDateTime createdAt = LocalDateTime.now();

    /** 最后投递时间 */
    private final LocalDateTime dispatchedAt;

    /** 最后错误信息 */
    private final String lastError;

    /** 租户 ID（多租户支持） */
    private final String tenantId;

    /** 幂等键（消费端去重） */
    private final String idempotencyKey;
}
