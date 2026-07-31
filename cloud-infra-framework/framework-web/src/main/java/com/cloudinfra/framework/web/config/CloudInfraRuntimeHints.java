package com.cloudinfra.framework.web.config;

import com.baomidou.mybatisplus.annotation.TableName;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.Set;

@AutoConfiguration
public class CloudInfraRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 注册通用的 Result 模型
        hints.reflection().registerType(com.cloudinfra.common.core.domain.Result.class, 
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, 
            MemberCategory.INVOKE_DECLARED_METHODS, 
            MemberCategory.DECLARED_FIELDS);

        // 注册 Spring Cloud RefreshScope 相关的类
        try {
            Class<?> refreshScopeClass = ClassUtils.forName("org.springframework.cloud.context.scope.refresh.RefreshScope", classLoader);
            hints.reflection().registerType(refreshScopeClass, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, 
                MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.DECLARED_FIELDS);
        } catch (ClassNotFoundException e) {
            // Ignore if not present
        }

        // 注册 MyBatis Plus 注解本身用于反射
        hints.reflection().registerType(TableName.class, MemberCategory.INVOKE_DECLARED_METHODS);
        
        // 实现自动类路径扫描注册
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(TableName.class));
        
        Set<org.springframework.beans.factory.config.BeanDefinition> candidateComponents = scanner.findCandidateComponents("com.cloudinfra");
        for (org.springframework.beans.factory.config.BeanDefinition bd : candidateComponents) {
            String className = bd.getBeanClassName();
            if (className != null) {
                try {
                    Class<?> clazz = ClassUtils.forName(className, classLoader);
                    hints.reflection().registerType(clazz, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, 
                        MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.DECLARED_FIELDS);
                } catch (ClassNotFoundException e) {
                    // Ignore
                }
            }
        }
        
        // 注册 hutool SpringUtil 相关类（用于 AOT 初始化）
        try {
            Class<?> springUtilClass = ClassUtils.forName("cn.hutool.extra.spring.SpringUtil", classLoader);
            hints.reflection().registerType(springUtilClass, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, 
                MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.DECLARED_FIELDS);
        } catch (ClassNotFoundException e) {
            // Ignore if hutool not present
        }
        
        // 注册 SpringUtil__BeanDefinitions 相关类（用于 Spring AOT 生成的配置类）
        try {
            Class<?> springUtilBeanDefClass = ClassUtils.forName("cn.hutool.extra.spring.SpringUtil__BeanDefinitions", classLoader);
            hints.reflection().registerType(springUtilBeanDefClass, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, 
                MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.DECLARED_FIELDS);
        } catch (ClassNotFoundException e) {
            // Ignore
        }
    }
}
