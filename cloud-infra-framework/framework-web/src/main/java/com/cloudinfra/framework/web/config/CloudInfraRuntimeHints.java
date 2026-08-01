package com.cloudinfra.framework.web.config;

import com.baomidou.mybatisplus.annotation.TableName;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Controller;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import java.util.Set;

@AutoConfiguration
public class CloudInfraRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // Register common Result model
        hints.reflection().registerType(com.cloudinfra.common.core.domain.Result.class,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.DECLARED_FIELDS);

        // Register Spring Cloud RefreshScope related classes
        try {
            Class<?> refreshScopeClass = ClassUtils.forName("org.springframework.cloud.context.scope.refresh.RefreshScope", classLoader);
            hints.reflection().registerType(refreshScopeClass, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.DECLARED_FIELDS);
        } catch (ClassNotFoundException e) {
            // Ignore if not present
        }

        // Register MyBatis Plus annotation for reflection
        hints.reflection().registerType(TableName.class, MemberCategory.INVOKE_DECLARED_METHODS);

        // Scan all Spring components in com.cloudinfra
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(TableName.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Component.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Service.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Repository.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Controller.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.context.annotation.Configuration.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.boot.autoconfigure.AutoConfiguration.class));

        Set<org.springframework.beans.factory.config.BeanDefinition> candidateComponents = scanner.findCandidateComponents("com.cloudinfra");
        for (org.springframework.beans.factory.config.BeanDefinition bd : candidateComponents) {
            String className = bd.getBeanClassName();
            if (className != null) {
                try {
                    Class<?> clazz = ClassUtils.forName(className, classLoader);
                    hints.reflection().registerType(clazz,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                        MemberCategory.DECLARED_FIELDS);
                } catch (ClassNotFoundException e) {
                    // Ignore
                }
            }
        }

        // Register SpringContextHolder (replaces hutool SpringUtil, Native-friendly)
        try {
            Class<?> springContextHolder = ClassUtils.forName("com.cloudinfra.framework.web.util.SpringContextHolder", classLoader);
            hints.reflection().registerType(springContextHolder,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.DECLARED_FIELDS);
        } catch (ClassNotFoundException e) {
            // Ignore if not present
        }
    }
}