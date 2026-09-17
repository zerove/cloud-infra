package com.cloudinfra.framework.mybatis.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxEventDispatcherTest {
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final StreamBridge bridge = mock(StreamBridge.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final OutboxEventDispatcher dispatcher = new OutboxEventDispatcher(repository, bridge, mapper);

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(dispatcher, "enabled", true);
        ReflectionTestUtils.setField(dispatcher, "batchSize", 100);
        when(bridge.send(eq("events"), any(Object.class))).thenReturn(true);
    }

    @Test
    void dispatchesEventCreatedByDefaultPublisher() {
        new OutboxEventPublisher(repository, mapper)
            .publishTransactional("events", "post", "1", "created", Map.of("code", "one"));
        ArgumentCaptor<OutboxEvent> published = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).insert(published.capture());
        OutboxEvent event = published.getValue();
        when(repository.lockAndFindPending(100)).thenReturn(List.of(event));

        dispatcher.dispatchPendingEvents();

        Map<?, ?> message = sentMessage();
        assertThat(message.get("tenantId")).isNull();
        assertThat(message.get("idempotencyKey")).isEqualTo("post:1:created");
        assertThat(message.get("payload")).isEqualTo(Map.of("code", "one"));
        verify(repository).markDispatched(event.getId());
        verify(repository, never()).markFailed(any(), any());
    }

    @Test
    void preservesTenantAndSupportsJsonNullPayload() {
        OutboxEvent event = event("tenant-A", "null");
        when(repository.lockAndFindPending(100)).thenReturn(List.of(event));
        dispatcher.dispatchPendingEvents();
        Map<?, ?> message = sentMessage();
        assertThat(message.get("tenantId")).isEqualTo("tenant-A");
        assertThat(message.containsKey("payload")).isTrue();
        assertThat(message.get("payload")).isNull();
        verify(repository).markDispatched(1L);
    }

    @Test
    void marksFailedWhenTransportRejectsMessage() {
        when(repository.lockAndFindPending(100)).thenReturn(List.of(event(null, "{}")));
        when(bridge.send(eq("events"), any(Object.class))).thenReturn(false);
        dispatcher.dispatchPendingEvents();
        verify(repository).markFailed(eq(1L), contains("send returned false"));
        verify(repository, never()).markDispatched(any());
    }

    @Test
    void preservesPlainTextPayload() {
        when(repository.lockAndFindPending(100)).thenReturn(List.of(event(null, "plain text")));
        dispatcher.dispatchPendingEvents();
        assertThat(sentMessage().get("payload")).isEqualTo("plain text");
    }

    private Map<?, ?> sentMessage() {
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(bridge).send(eq("events"), sent.capture());
        return (Map<?, ?>) sent.getValue();
    }

    private OutboxEvent event(String tenant, String payload) {
        return OutboxEvent.builder().id(1L).aggregateType("post").aggregateId("1")
            .eventType("created").bindingName("events").tenantId(tenant)
            .idempotencyKey("post:1:created").payload(payload).build();
    }
}
