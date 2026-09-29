package com.cloudinfra.framework.security.grpc;

import com.cloudinfra.common.core.security.CallerContextBinder;
import com.cloudinfra.common.core.security.PlatformCaller;
import com.cloudinfra.common.util.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-02 安全边界: 调用方身份 → Spring Security 上下文桥接。
 *
 * <p>验证 gRPC 业务层可直接复用既有 {@code SecurityUtils} / {@code SecurityContextHolder}
 * 判定逻辑, 且上下文不跨调用残留。
 */
class SpringSecurityCallerContextBinderTest {

    private final SpringSecurityCallerContextBinder binder = new SpringSecurityCallerContextBinder();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bindExposesCallerThroughExistingSecurityUtils() {
        binder.bind(new PlatformCaller(88L, "alice", "tenant-x", "grpc:svc/Method"));

        assertThat(SecurityUtils.getUserId()).isEqualTo(88L);
        assertThat(SecurityUtils.getUsername()).isEqualTo("alice");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();
    }

    @Test
    void clearRemovesContextSoNextCallStartsClean() {
        binder.bind(new PlatformCaller(88L, "alice", "tenant-x", "grpc:svc/Method"));
        binder.clear();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(SecurityUtils.getUserId()).isNull();
        assertThat(SecurityUtils.getUsername()).isEqualTo("guest");
    }

    @Test
    void clearIsSafeWhenNothingWasBound() {
        binder.clear();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void bindWithoutUserIdentityExposesServiceLevelCall() {
        binder.bind(new PlatformCaller(null, null, "default", "grpc:svc/Method"));

        // 服务间调用没有终端用户, SecurityUtils 拿不到 userId/username,
        // 业务层据此区分"服务调用"与"用户调用"。
        assertThat(SecurityUtils.getUserId()).isNull();
        assertThat(SecurityUtils.getUsername()).isNull();
    }

    @Test
    void permissionsStayEmptyUntilMethodLevelAuthorizationLands() {
        binder.bind(new PlatformCaller(88L, "alice", "tenant-x", "grpc:svc/Method"));

        assertThat(SecurityUtils.getLoginUser().getPermissions()).isEmpty();
    }

    @Test
    void autoConfigurationStaysInactiveWhenGrpcFrameworkAbsent() {
        // framework-security 自身不依赖 framework-grpc, 因此桥接装配不应生效,
        // 避免 gateway / auth 等未引入 gRPC 的服务多出无用 bean。
        // "framework-grpc 在场时正确装配" 由 system-module 的真实装配测试覆盖。
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GrpcSecurityBridgeAutoConfiguration.class))
            .run(context -> assertThat(context).doesNotHaveBean(CallerContextBinder.class));
    }
}
