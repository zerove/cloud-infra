package com.cloudinfra.common.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户标识字面量的存在性守护 (2026-10-18, 审计缺陷 D2)。
 *
 * <p>这两个字面量是线上协议的一部分: claim 名跨 auth→网关 的 token 边界,
 * 头名跨网关→下游 的网络边界。改名不会编译失败, 只会让租户隔离无声失效,
 * 因此显式锁定 —— 这是对注释里那两个从未存在过的
 * {@code TenantClaimNameConsistencyTest} / {@code TenantHeaderNameConsistencyTest}
 * 的真替代: 共享常量保证"两端一致", 本测试保证"值本身不改"。
 */
class TenantClaimsTest {

    @Test
    @DisplayName("claim 名字面量锁定: auth 写入与网关读取的正是同一个名字")
    void tenantIdClaimNameIsStable() {
        assertThat(TenantClaims.TENANT_ID_CLAIM).isEqualTo("tenantId");
        assertThat(TenantContextFilter.TENANT_HEADER).isEqualTo("X-Tenant-Id");
    }

    @Test
    @DisplayName("租户头常量与 claim 常量类型/用途不同, 不得互相混用")
    void headerAndClaimAreDistinct() {
        assertThat(TenantContextFilter.TENANT_HEADER)
            .isNotEqualTo(TenantClaims.TENANT_ID_CLAIM);
    }
}
