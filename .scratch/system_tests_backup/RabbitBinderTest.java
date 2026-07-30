package com.cloudinfra.system;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.binder.rabbit.RabbitMessageChannelBinder;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RabbitBinderTest {

    @Test
    void testRabbitBinderClassExists() {
        assertNotNull(RabbitMessageChannelBinder.class);
    }
}
