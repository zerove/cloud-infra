package com.cloudinfra.system.task;

import com.cloudinfra.system.domain.SysTxMessage;
import com.cloudinfra.system.service.SysTxMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Slf4j
@Component
public class TxMessageRetryTask {

    @Autowired
    private SysTxMessageService txMessageService;

    @Scheduled(fixedDelay = 60000)
    public void retryPendingMessages() {
        log.info("开始处理待发送事务消息...");
        List<SysTxMessage> pendingMessages = txMessageService.findPendingMessages();
        
        for (SysTxMessage message : pendingMessages) {
            try {
                log.info("处理消息: {}", message.getId());
                // TODO: 实现具体的消息发送逻辑 (如 MQ 发送)
                
                // 模拟发送成功
                txMessageService.updateMessageStatus(message.getId(), 1);
            } catch (Exception e) {
                log.error("消息处理失败: {}", message.getId(), e);
                // 可以在此更新 retry_count
            }
        }
        log.info("待发送事务消息处理完成。");
    }
}
