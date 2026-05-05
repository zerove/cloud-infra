package com.cloudinfra.system.task;

import com.cloudinfra.system.domain.SysTxMessage;
import com.cloudinfra.system.service.SysTxMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TxMessageRetryTaskTest {

    @Mock
    private SysTxMessageService txMessageService;

    @Mock
    private StreamBridge streamBridge;

    @InjectMocks
    private TxMessageRetryTask txMessageRetryTask;

    @Test
    public void testRetryPendingMessages() {
        SysTxMessage message = new SysTxMessage();
        message.setId(1L);
        List<SysTxMessage> pendingMessages = Collections.singletonList(message);
        when(txMessageService.findPendingMessages()).thenReturn(pendingMessages);

        txMessageRetryTask.retryPendingMessages();

        verify(streamBridge).send(eq("txMessageOut-out-0"), any());
        verify(txMessageService).updateMessageStatus(1L, 1);
    }
}
