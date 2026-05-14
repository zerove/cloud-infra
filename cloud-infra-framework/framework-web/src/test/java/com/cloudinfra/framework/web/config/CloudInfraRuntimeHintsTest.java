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

        // 检查 MyBatis TableName 注解的反射提示是否已注册
        // 在实际 Spring AOT 中，直接检查提示信息非常困难。
        // 我们假设如果已注册，就可以通过 TypeHints 验证。
        assertTrue(hints.reflection().getTypeHint(com.baomidou.mybatisplus.annotation.TableName.class) != null, 
            "TableName annotation should be registered for reflection");
    }
}
