package com.cloudinfra.system.task;

import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public class TxMessageRetryTaskTest extends BaseIntegrationTest {

    @Container
    static RabbitMQContainer rabbitMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));

    @Autowired
    private TxMessageRetryTask txMessageRetryTask;

    @MockitoBean
    private com.cloudinfra.system.service.SysTxMessageService txMessageService;

    @Test
    void shouldSendPendingMessagesToRabbitMQ() {
        // Prepare mock data
        com.cloudinfra.system.domain.SysTxMessage mockMessage = new com.cloudinfra.system.domain.SysTxMessage();
        mockMessage.setId(1001L);
        mockMessage.setContent("{\"test\":\"data\"}");
        mockMessage.setTopic("tx.message.topic");

        org.mockito.Mockito.when(txMessageService.findPendingMessages())
                .thenReturn(java.util.Collections.singletonList(mockMessage));

        // Execute task
        txMessageRetryTask.retryPendingMessages();

        // Verify update was called meaning send succeeded
        org.mockito.Mockito.verify(txMessageService, org.mockito.Mockito.times(1))
                .updateMessageStatus(1001L, 1);
    }
}
