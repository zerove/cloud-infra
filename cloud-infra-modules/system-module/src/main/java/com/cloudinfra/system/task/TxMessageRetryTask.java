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

    private static final int MAX_RETRY_ATTEMPTS = 5;

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
                    log.info("处理消息: {}, 当前重试次数: {}", message.getId(), message.getRetryCount());
                    String traceId = traceUtils.getCurrentTraceId();
                    if (traceId == null || traceId.isEmpty()) {
                        traceId = UUID.randomUUID().toString();
                    }
                    
                    CloudMessage<SysTxMessage> cloudMessage = CloudMessage.<SysTxMessage>builder()
                            .traceId(traceId)
                            .data(message)
                            .build();
                    streamBridge.send("txMessageOut-out-0", cloudMessage);
                    
                    // 发送成功 $\rightarrow$ 更新状态为已发送 (1)
                    txMessageService.updateMessageStatus(message.getId(), 1);
                } catch (Exception e) {
                    int currentRetry = (message.getRetryCount() == null) ? 0 : message.getRetryCount();
                    int nextRetry = currentRetry + 1;
                    
                    if (nextRetry >= MAX_RETRY_ATTEMPTS) {
                        log.error("消息 {} 已达到最大重试次数 ({})，标记为处理失败 (状态: 3)", message.getId(), MAX_RETRY_ATTEMPTS);
                        txMessageService.updateMessageStatus(message.getId(), 3); // 3: 处理失败
                    } else {
                        log.warn("消息 {} 发送失败，准备下一次重试. 当前次数: {}", message.getId(), nextRetry);
                        txMessageService.updateRetryCount(message.getId(), nextRetry);
                    }
                }
            }
            log.info("待发送事务消息处理完成。");
            return null;
        });
    }
}
