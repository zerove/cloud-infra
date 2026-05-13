package com.cloudinfra.framework.web.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CloudInfraRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 注册通用的 Result 模型
        hints.reflection().registerType(com.cloudinfra.common.core.domain.Result.class, 
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, 
            MemberCategory.INVOKE_DECLARED_METHODS, 
            MemberCategory.DECLARED_FIELDS);
        
        // 自动注册 Entity 和 Mapper 的逻辑将在后续 Task 5 中通过类路径扫描实现
        // 此处先注册框架核心类
    }
}
