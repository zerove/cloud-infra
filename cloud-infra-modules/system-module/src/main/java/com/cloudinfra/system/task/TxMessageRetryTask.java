package com.cloudinfra.system.task;

import com.cloudinfra.system.domain.SysTxMessage;
import com.cloudinfra.system.service.SysTxMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.scheduling.annotation.Scheduled;
import com.cloudinfra.framework.tracing.TraceUtils;
import org.springframework.stereotype.Component;
import com.cloudinfra.common.core.messaging.CloudMessage;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class TxMessageRetryTask {

    @Autowired
    private SysTxMessageService txMessageService;

    @Autowired
    private StreamBridge streamBridge;

    @Autowired
    private TraceUtils traceUtils;

    @Scheduled(fixedDelay = 60000)
    public void retryPendingMessages() {
        traceUtils.createCustomSpan("TxMessageRetryTask.retryPendingMessages", () -> {
            log.info("开始处理待发送事务消息...");
            List<SysTxMessage> pendingMessages = txMessageService.findPendingMessages();

            for (SysTxMessage message : pendingMessages) {
                try {
                    log.info("发送消息到消息队列: {}, 当前重试次数: {}", message.getId(), message.getRetryCount());
                    String traceId = traceUtils.getCurrentTraceId();
                    if (traceId == null || traceId.isEmpty()) {
                        traceId = UUID.randomUUID().toString();
                    }

                    CloudMessage<SysTxMessage> cloudMessage = CloudMessage.<SysTxMessage>builder()
                            .traceId(traceId)
                            .data(message)
                            .build();
                    streamBridge.send("sys.tx.message.retry", cloudMessage);

                    // 发送到 MQ 成功 -> 更新状态为已发送 (1)
                    // 后续由 TxMessageConsumer 消费处理，成功则更新为 2，失败重试耗尽进入 DLQ 更新为 3
                    txMessageService.updateMessageStatus(message.getId(), 1);
                } catch (Exception e) {
                    log.error("消息 {} 发送到 MQ 失败: {}", message.getId(), e.getMessage(), e);
                    // 发送到 MQ 失败，保持状态为待发送 (0) 或处理失败 (3)，下次定时任务重试
                    // 这里不增加 retryCount，retryCount 由消费端重试控制
                }
            }
            log.info("待发送事务消息发送完成。");
            return null;
        });
    }
}