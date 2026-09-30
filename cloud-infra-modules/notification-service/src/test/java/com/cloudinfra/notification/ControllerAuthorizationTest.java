package com.cloudinfra.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.TreeSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 {@code @PreAuthorize} 覆盖。
 *
 * <p><b>为什么在 notification 也放一份</b>: system-module 于 2026-09-30 审计中借此反射扫描
 * 查出 3 个"认证即可读"的端点, 证明该类缺口是真实发生过的而非理论风险。
 * 但那份守护当时只覆盖 {@code com.cloudinfra.system}, 而 {@code NotificationServiceIntegrationTest}
 * 为让端点可测而把测试过滤器放宽成 {@code anyRequest().authenticated()} ——
 * 也就是说 notification 模块的端点权限<b>当时没有任何自动化守护</b>。
 * 本测试补上该缺口, 使"认证即可读"这类退化在 notification 也无法悄悄合入。</p>
 *
 * <p>纯结构性断言, 不依赖容器, 每次 {@code mvn test} 执行。</p>
 */
class ControllerAuthorizationTest {

    private static final String SCAN_PACKAGE = "com.cloudinfra.notification";

    /**
     * 允许不带 {@code @PreAuthorize} 的路径前缀。
     *
     * <p>本模块<b>零例外</b>: notification 没有 {@code /internal/} 服务间端点,
     * 全部端点都是用户面 API。因此这里刻意留空 —— 一旦将来出现例外,
     * 下面的 {@code allowlistStaysMinimal} 会立刻变红, 迫使提出者说明理由。</p>
     */
    private static final List<String> UNPROTECTED_ALLOWLIST = List.of();

    @Test
    @DisplayName("所有端点都必须带 @PreAuthorize")
    void everyEndpointRequiresAuthorization() throws Exception {
        Set<String> gaps = new TreeSet<>();
        int total = 0;

        for (BeanDefinition definition : scanControllers()) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            String prefix = prefixOf(type);
            boolean classLevelAuth = AnnotatedElementUtils.hasAnnotation(type, PreAuthorize.class);

            for (Method method : type.getDeclaredMethods()) {
                String path = methodPath(method);
                if (path == null) {
                    continue;
                }
                total++;
                String full = prefix + path;
                if (classLevelAuth || AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class)) {
                    continue;
                }
                if (UNPROTECTED_ALLOWLIST.stream().anyMatch(full::startsWith)) {
                    continue;
                }
                gaps.add(type.getSimpleName() + "  " + full);
            }
        }

        assertThat(total).as("扫描到的端点总数, 非 0 说明扫描失效").isGreaterThan(0);
        assertThat(gaps)
            .as("以下端点只要求'已认证'而不要求任何权限 —— 认证通过即可越权读")
            .isEmpty();
    }

    @Test
    @DisplayName("允许清单必须保持最小（防止被当成绕过鉴权的后门）")
    void allowlistStaysMinimal() {
        assertThat(UNPROTECTED_ALLOWLIST)
            .as("notification 无 /internal/ 端点, 新增例外前请先说明该端点为何真的不需要权限判定")
            .isEmpty();
    }

    private static Set<BeanDefinition> scanControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        return scanner.findCandidateComponents(SCAN_PACKAGE);
    }

    private static String prefixOf(Class<?> type) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        if (mapping == null) {
            return "";
        }
        if (mapping.path().length > 0 && mapping.path()[0] != null && !mapping.path()[0].isEmpty()) {
            return mapping.path()[0];
        }
        return mapping.value().length > 0 && mapping.value()[0] != null ? mapping.value()[0] : "";
    }

    /** 返回方法级映射路径；无映射注解时返回 {@code null} */
    private static String methodPath(Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null) {
            return null;
        }
        String path = null;
        if (mapping.path().length > 0 && mapping.path()[0] != null && !mapping.path()[0].isEmpty()) {
            path = mapping.path()[0];
        } else if (mapping.value().length > 0 && mapping.value()[0] != null && !mapping.value()[0].isEmpty()) {
            path = mapping.value()[0];
        }
        return path;
    }
}
