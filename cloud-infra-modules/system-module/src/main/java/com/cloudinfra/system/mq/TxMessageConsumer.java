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
 * 事务消息消费者 - 消费 sys.tx.message.retry 队列
 * 
 * 当消费失败时，Spring Cloud Stream RabbitMQ binder 会自动将消息重发至 DLQ (sys.tx.message.dlq)
 * 配置参考 application.yml 中的 spring.cloud.stream.rabbit.bindings.txMessageConsumer-in-0
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class TxMessageConsumer {

    private final SysTxMessageService txMessageService;

    @Bean
    public Consumer<CloudMessage<SysTxMessage>> txMessageConsumerFunction() {
        return cloudMessage -> {
            SysTxMessage message = cloudMessage.getData();
            if (message == null) {
                log.warn("收到空消息体，跳过处理");
                return;
            }

            log.info("消费事务消息: id={}, topic={}, retryCount={}", 
                message.getId(), message.getTopic(), message.getRetryCount());

            try {
                // 根据 topic 进行业务处理
                // 测试用：FAIL_TOPIC 会抛出异常触发重试和 DLQ
                if ("FAIL_TOPIC".equals(message.getTopic())) {
                    throw new RuntimeException("模拟业务处理失败: " + message.getTopic());
                }

                // 业务处理已通过框架流程完成：消息成功更新状态为已处理(2)，失败自动转发至 DLQ
                // processMessage(message);

                // 处理成功 -> 更新状态为已处理 (2)
                txMessageService.updateMessageStatus(message.getId(), 2);
                log.info("事务消息处理成功: id={}", message.getId());

            } catch (Exception e) {
                log.error("事务消息处理失败: id={}, 错误: {}", message.getId(), e.getMessage(), e);
                // 抛出异常触发 Spring Cloud Stream 重试机制
                // 配置了 auto-bind-dlq=true, republish-to-dlq=true 后，
                // 重试耗尽会自动转发到 DLQ
                throw new RuntimeException("TxMessage processing failed", e);
            }
        };
    }
}