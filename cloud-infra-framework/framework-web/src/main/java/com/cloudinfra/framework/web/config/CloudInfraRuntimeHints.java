package com.cloudinfra.framework.web.config;

import com.baomidou.mybatisplus.annotation.TableName;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.util.ClassUtils;

/**
 * Cloud-Infra 运行时提示注册器
 * 仅注册关键动态加载类，避免 reachability-metadata 膨胀导致 native-image 分析阶段内存耗尽。
 * 其余类由 Spring AOT 生成的 __BeanDefinitions 自动注册。
 */
@AutoConfiguration
public class CloudInfraRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 注册通用 Result 模型
        hints.reflection().registerType(com.cloudinfra.common.core.domain.Result.class,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS);

        // 注册 MyBatis Plus 注解
        hints.reflection().registerType(TableName.class, MemberCategory.INVOKE_DECLARED_METHODS);

        // 注册 SpringContextHolder（替代 hutool SpringUtil）
        try {
            Class<?> springContextHolder = ClassUtils.forName("com.cloudinfra.framework.web.util.SpringContextHolder", classLoader);
            hints.reflection().registerType(springContextHolder,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS);
        } catch (ClassNotFoundException e) {
            // Ignore if not present
        }
    }
}