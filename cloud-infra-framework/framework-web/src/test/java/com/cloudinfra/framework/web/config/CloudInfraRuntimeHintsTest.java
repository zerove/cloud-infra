package com.cloudinfra.framework.web.config;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudInfraRuntimeHintsTest {

    @Test
    void shouldRegisterMyBatisAnnotations() {
        RuntimeHints hints = new RuntimeHints();
        CloudInfraRuntimeHints registrar = new CloudInfraRuntimeHints();
        registrar.registerHints(hints, this.getClass().getClassLoader());

        // Check if reflection hints for MyBatis TableName annotation are present
        // In real Spring AOT, checking hints directly is hard.
        // We will assume that if we register them, we can verify it via the TypeHints.
        assertTrue(hints.reflection().getTypeHint(com.baomidou.mybatisplus.annotation.TableName.class) != null, 
            "TableName annotation should be registered for reflection");
    }
}
