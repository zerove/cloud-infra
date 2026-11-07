package com.cloudinfra.system;

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
 * <p><b>为什么需要</b>: 2026-09-30 反射级扫描发现 3 个端点只要求"已认证"而未要求任何权限
 * —— {@code /system/rbac/user/{userId}/permissions}、{@code /system/resource-mapping/effective-nodes}、
 * {@code /system/permission/list}。同一控制器内其余端点都有 {@code @PreAuthorize},
 * 属纯粹的遗漏而非有意设计。AGENTS.md 曾声称"所有 Controller 已补齐 {@code @PreAuthorize}",
 * 与事实不符。</p>
 *
 * <p>纯结构性断言, 不依赖容器, 每次 {@code mvn test} 执行。</p>
 */
class ControllerAuthorizationTest {

    /**
     * 扫描范围。
     *
     * <p>2026-10-17 起由 {@code com.cloudinfra.system} 放宽到 {@code com.cloudinfra}:
     * 原范围只看本模块, 于是 {@code cloud-infra-common} 的 {@code ErrorCodeController}
     * 结构性不可见 —— 它被每个应用的 {@code scanBasePackages("com.cloudinfra")} 扫入,
     * 却在全部服务上无 {@code @PreAuthorize} 暴露了完整错误码表, 而守护全绿。
     *
     * <p>类路径上只含 system-module 及其依赖 (common / framework / api), 因此放宽后
     * 实际覆盖 system + common + framework 中的控制器; gateway 与 auth 不在本模块
     * 类路径上, 由各自模块的守护负责。
     */
    private static final String SCAN_PACKAGE = "com.cloudinfra";

    /**
     * 允许不带 {@code @PreAuthorize} 的路径前缀。空列表 = 零例外。
     * <ul>
     *   <li>{@code /internal/}：服务间契约端点，由 {@code SystemSecurityConfig} 的
     *       {@code /internal/**} 单点鉴权决策（HMAC 验签）保护，语义上不是"用户权限"而是
     *       "调用方身份"，用 {@code @PreAuthorize} 表达反而会破坏 Feign 调用。</li>
     * </ul>
     */
    private static final List<String> UNPROTECTED_ALLOWLIST = List.of("/internal/");

    @Test
    @DisplayName("所有非 /internal/ 端点都必须带 @PreAuthorize")
    void everyEndpointRequiresAuthorization() throws Exception {
        Set<String> gaps = new TreeSet<>();
        Set<String> scannedControllers = new TreeSet<>();
        int total = 0;

        for (BeanDefinition definition : scanControllers()) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            scannedControllers.add(type.getSimpleName());
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
        assertThat(scannedControllers)
            .as("扫描到的控制器应含 common 下的 ErrorCodeController —— 若缺失说明扫描范围又被收窄")
            .contains("ErrorCodeController");
        assertThat(gaps)
            .as("以下端点只要求'已认证'而不要求任何权限 —— 认证通过即可越权读")
            .isEmpty();
    }

    @Test
    @DisplayName("允许清单必须保持最小（防止被当成绕过鉴权的后门）")
    void allowlistStaysMinimal() {
        assertThat(UNPROTECTED_ALLOWLIST)
            .as("新增例外前请先确认: 该端点是否真的不需要任何权限判定")
            .containsExactly("/internal/");
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
