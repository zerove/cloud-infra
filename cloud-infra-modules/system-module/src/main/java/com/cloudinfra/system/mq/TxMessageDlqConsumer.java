package com.cloudinfra.system.mq;

import com.cloudinfra.common.core.messaging.CloudMessage;
import com.cloudinfra.system.domain.SysTxMessage;
import com.cloudinfra.system.service.SysTxMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/**
 * 事务消息死信队列消费者 - 处理 sys.tx.message.dlq
 * 
 * 当主消费者重试耗尽后，消息会自动路由至此队列。
 * 这里负责记录告警、人工介入标记、补偿逻辑等。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class TxMessageDlqConsumer {

    private final SysTxMessageService txMessageService;

    @Bean
    public Consumer<CloudMessage<SysTxMessage>> txMessageDlqConsumerFunction() {
        return cloudMessage -> {
            SysTxMessage message = cloudMessage.getData();
            if (message == null) {
                log.error("DLQ 收到空消息体，traceId={}", cloudMessage.getTraceId());
                return;
            }

            log.error("消息进入死信队列: id={}, topic={}, traceId={}, retryCount={}", 
                message.getId(), message.getTopic(), cloudMessage.getTraceId(), message.getRetryCount());

            // 1. 标记为处理失败 (3) - 已在重试任务中标记，此处确认
            txMessageService.updateMessageStatus(message.getId(), 3);

            // 2. 记录告警日志（可对接告警系统）
            // alertService.sendAlert("TxMessage DLQ", "消息处理彻底失败，需人工介入: " + message.getId());

            // 3. 补偿逻辑：根据 topic 类型执行特定补偿
            // compensate(message);

            // 4. 可选：将失败详情持久化到单独表供运维查询
            // dlqRecordService.save(message, cloudMessage.getTraceId());
        };
    }
}