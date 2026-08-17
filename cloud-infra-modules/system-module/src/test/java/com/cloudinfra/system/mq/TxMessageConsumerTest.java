package com.cloudinfra.system.mq;

import com.cloudinfra.common.core.messaging.CloudMessage;
import com.cloudinfra.system.domain.SysTxMessage;
import com.cloudinfra.system.service.SysTxMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TxMessageConsumer 单元测试
 * 测试消费者的业务处理逻辑：成功、异常、空消息
 */
@ExtendWith(MockitoExtension.class)
class TxMessageConsumerTest {

    @Mock
    private SysTxMessageService txMessageService;

    @InjectMocks
    private TxMessageConsumer txMessageConsumer;

    private Consumer<CloudMessage<SysTxMessage>> consumer;

    @BeforeEach
    void setUp() {
        consumer = txMessageConsumer.txMessageConsumerFunction();
    }

    @Test
    void shouldProcessMessageSuccessfully() {
        // Given: 正常消息
        SysTxMessage message = new SysTxMessage();
        message.setId(1L);
        message.setTopic("TEST_TOPIC");
        message.setContent("{\"key\":\"value\"}");
        message.setStatus(1);
        message.setRetryCount(0);

        CloudMessage<SysTxMessage> cloudMessage = CloudMessage.<SysTxMessage>builder()
                .traceId(UUID.randomUUID().toString())
                .data(message)
                .build();

        // When: 调用消费者
        consumer.accept(cloudMessage);

        // Then: 应更新状态为已处理 (2)
        verify(txMessageService).updateMessageStatus(eq(1L), eq(2));
    }

    @Test
    void shouldThrowExceptionWhenTopicIsFailTopic() {
        // Given: 会导致失败的消息
        SysTxMessage message = new SysTxMessage();
        message.setId(2L);
        message.setTopic("FAIL_TOPIC");
        message.setContent("{\"fail\":true}");
        message.setStatus(1);
        message.setRetryCount(0);

        CloudMessage<SysTxMessage> cloudMessage = CloudMessage.<SysTxMessage>builder()
                .traceId(UUID.randomUUID().toString())
                .data(message)
                .build();

        // When/Then: 应抛出异常
        RuntimeException exception = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> consumer.accept(cloudMessage)
        );

        // 验证异常消息
        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("TxMessage processing failed");

        // 验证没有调用更新状态
        verify(txMessageService, never()).updateMessageStatus(anyLong(), anyInt());
    }

    @Test
    void shouldHandleNullMessageGracefully() {
        // Given: 空消息体
        CloudMessage<SysTxMessage> cloudMessage = CloudMessage.<SysTxMessage>builder()
                .traceId(UUID.randomUUID().toString())
                .data(null)
                .build();

        // When: 调用消费者（不应抛出异常）
        consumer.accept(cloudMessage);

        // Then: 无异常，且未调用任何 service 方法
        verifyNoInteractions(txMessageService);
    }

    @Test
    void shouldUpdateStatusToProcessedWhenSuccessful() {
        // Given
        SysTxMessage message = new SysTxMessage();
        message.setId(100L);
        message.setTopic("NORMAL_TOPIC");
        message.setStatus(1);

        CloudMessage<SysTxMessage> cloudMessage = CloudMessage.<SysTxMessage>builder()
                .traceId("trace-123")
                .data(message)
                .build();

        // When
        consumer.accept(cloudMessage);

        // Then
        verify(txMessageService).updateMessageStatus(100L, 2);
        verify(txMessageService, never()).updateRetryCount(anyLong(), anyInt());
    }

    @Test
    void shouldHandleExceptionAndNotUpdateStatus() {
        // Given: 任何会导致异常的情况
        SysTxMessage message = new SysTxMessage();
        message.setId(200L);
        message.setTopic("FAIL_TOPIC");

        CloudMessage<SysTxMessage> cloudMessage = CloudMessage.<SysTxMessage>builder()
                .traceId("trace-456")
                .data(message)
                .build();

        // When
        try {
            consumer.accept(cloudMessage);
        } catch (RuntimeException ignored) {
            // 预期抛出
        }

        // Then: 既不更新状态为已处理，也不更新重试次数
        // （重试次数由 RabbitMQ/Spring Cloud Stream 管理）
        verify(txMessageService, never()).updateMessageStatus(anyLong(), anyInt());
        verify(txMessageService, never()).updateRetryCount(anyLong(), anyInt());
    }
}