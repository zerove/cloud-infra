package com.cloudinfra.common.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 平台安全上下文测试
 */
class PlatformSecurityContextTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReturnEmptyWhenNoAuthentication() {
        assertThat(PlatformSecurityContext.getCurrent()).isEmpty();
        assertThat(PlatformSecurityContext.isAuthenticated()).isFalse();
    }

    @Test
    void shouldReturnPrincipalWhenPlatformPrincipalSet() {
        PlatformPrincipal principal = PlatformPrincipal.builder()
            .userId(1L)
            .username("admin")
            .tenantId("tenant-001")
            .permissions(Set.of("system:user:list", "system:user:add"))
            .build();

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, Set.of())
        );

        assertThat(PlatformSecurityContext.isAuthenticated()).isTrue();
        assertThat(PlatformSecurityContext.getUserId()).hasValue(1L);
        assertThat(PlatformSecurityContext.getUsername()).isEqualTo("admin");
        assertThat(PlatformSecurityContext.getTenantId()).isEqualTo("tenant-001");
        assertThat(PlatformSecurityContext.hasPermission("system:user:list")).isTrue();
        assertThat(PlatformSecurityContext.hasPermission("system:user:delete")).isFalse();
    }

    @Test
    void shouldThrowWhenCurrentCalledWithoutAuth() {
        assertThatThrownBy(PlatformSecurityContext::current)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No authenticated user");
    }

    @Test
    void shouldHandleStringUsernamePrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("guest", null, Set.of())
        );
        assertThat(PlatformSecurityContext.getUsername()).isEqualTo("guest");
        assertThat(PlatformSecurityContext.getUserId()).isEmpty();
    }
}