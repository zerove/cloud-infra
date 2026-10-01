package com.cloudinfra.system;

import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护本服务的组件扫描范围<b>覆盖 {@link InternalCallSecretProvider}</b>。
 *
 * <h2>为什么这个守护存在</h2>
 *
 * <p>2026-10-01 开启部署态多租户后, 出现一条只在部署后才炸的链:</p>
 * <pre>
 * cloud-infra.tenant.enabled=true
 *   → TenantContextFilter 注册, 内部用 ObjectProvider 取密钥
 *   → 取不到 → secret = null → 该过滤器对带租户头的请求 fail-closed 403
 *   → 网关给每个已认证请求都注入租户头 → 全站 403
 * </pre>
 *
 * <p>密钥类位于 {@code com.cloudinfra.common.core.crypto}, <b>在本服务的包之外</b>,
 * 因此能否拿到它完全取决于主类的组件扫描配置 —— 编译期与常规单测都测不出来。</p>
 *
 * <p>本服务此前就踩过同一个坑: {@code SystemApplication} 的 excludeFilters 曾用
 * {@code com\\.cloudinfra\\.starter\\..*} 整体排除 starter, 连带把密钥类也排掉了
 * （代码注释里有记录）。为防止再次发生, 把该约束固化为测试。
 * 三个业务服务各有一份同名守护, 因为 starter 不反向依赖业务模块。</p>
 */
class TenantFilterSecretWiringTest {

    private static final String SECRET_PACKAGE = "com.cloudinfra.common.core.crypto";

    @Test
    @DisplayName("组件扫描范围覆盖密钥类所在包")
    void scanRangeCoversCommonPackage() {
        assertThat(scanBasePackages())
            .as("SystemApplication 的扫描范围必须包含 %s 这个包本身或其某个祖先包, 否则扫不到密钥类; "
                + "开启多租户后该服务密钥为 null, 所有经网关的已认证请求都会被 403",
                SECRET_PACKAGE)
            .isNotEmpty()
            .anyMatch(TenantFilterSecretWiringTest::coversSecretPackage);
    }

    @Test
    @DisplayName("excludeFilters 不得排除密钥类所在包")
    void excludeFiltersDoNotCoverSecretPackage() {
        ComponentScan scan = AnnotationUtils.findAnnotation(SystemApplication.class, ComponentScan.class);
        if (scan == null) {
            return;
        }
        for (var filter : scan.excludeFilters()) {
            String pattern = String.valueOf(filter.pattern());
            assertThat(pattern)
                .as("排除模式 %s 会连带排除 %s (密钥类), 开启多租户后全站 403",
                    pattern, SECRET_PACKAGE)
                .doesNotMatch(".*cloudinfra\\.(common|starter)\\..*");
        }
    }

    private static String[] scanBasePackages() {
        ComponentScan scan = AnnotationUtils.findAnnotation(SystemApplication.class, ComponentScan.class);
        if (scan != null && scan.basePackages().length > 0) {
            return scan.basePackages();
        }
        SpringBootApplication boot = AnnotationUtils.findAnnotation(SystemApplication.class,
            SpringBootApplication.class);
        return boot == null ? new String[0] : boot.scanBasePackages();
    }

    /**
     * 候选扫描包 P 是否能覆盖密钥类所在的包。
     *
     * <p><b>不能用 {@code P.startsWith("com.cloudinfra")} 判断</b>: 那会让
     * {@code com.cloudinfra.system} 也算"覆盖", 而它并不包含密钥类所在的
     * {@code com.cloudinfra.common} —— 这种假守护在真实收窄扫描范围时依然全绿。
     * 2026-10-01 的负向对照正是靠抓到这个错误断言才发现它没起作用。</p>
     *
     * <p>正确的包含关系: 密钥包等于 P, 或以 {@code P + "."} 为前缀。</p>
     */
    private static boolean coversSecretPackage(String candidate) {
        return SECRET_PACKAGE.equals(candidate) || SECRET_PACKAGE.startsWith(candidate + ".");
    }
}