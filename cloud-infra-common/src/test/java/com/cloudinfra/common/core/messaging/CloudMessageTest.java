package com.cloudinfra.common.core.messaging;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CloudMessageTest {

    @Test
    public void testCloudMessageBuilderAndGetters() {
        String traceId = "test-trace-id";
        String data = "test-data";

        CloudMessage<String> message = CloudMessage.<String>builder()
                .traceId(traceId)
                .data(data)
                .build();

        assertEquals(traceId, message.getTraceId());
        assertEquals(data, message.getData());
    }

    @Test
    public void testNoArgsConstructor() {
        CloudMessage<String> message = new CloudMessage<>();
        message.setTraceId("new-trace");
        message.setData("new-data");

        assertEquals("new-trace", message.getTraceId());
        assertEquals("new-data", message.getData());
    }
}
