package com.cloudinfra.common.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 租户上下文测试
 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldSetAndGetTenantId() {
        TenantContext.setTenantId("tenant-001");
        assertThat(TenantContext.getTenantId()).hasValue("tenant-001");
        assertThat(TenantContext.hasTenant()).isTrue();
    }

    @Test
    void shouldReturnDefaultWhenEmpty() {
        assertThat(TenantContext.getTenantIdOrDefault()).isEqualTo("default");
    }

    @Test
    void shouldClearTenant() {
        TenantContext.setTenantId("tenant-001");
        TenantContext.clear();
        assertThat(TenantContext.hasTenant()).isFalse();
        assertThat(TenantContext.getTenantIdOrDefault()).isEqualTo("default");
    }

    @Test
    void shouldScopeKeyWithTenant() {
        TenantContext.setTenantId("tenant-001");
        assertThat(TenantContext.scopedKey("user:1")).isEqualTo("tenant-001:user:1");
    }

    @Test
    void shouldGenerateUniqueTenantId() {
        String t1 = TenantContext.generateTenantId();
        String t2 = TenantContext.generateTenantId();
        assertThat(t1).startsWith("tenant-");
        assertThat(t1).isNotEqualTo(t2);
    }
}