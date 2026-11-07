package com.cloudinfra.system;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S4 运行时证据骨架（多租户 JWT→TenantContext→SQL）。
 *
 * <p>需在具备网关/鉴权与真实数据源的环境中，针对 {@code file/notification/system}
 * 各选 1 条读写路径，证明跨租户不可见且同租户可见。
 *
 * <p>完成后移除 {@link Disabled} 并记录结构性/触发/端到端证据。
 */
@Disabled("S4 evidence skeleton: enable with real infra and cross-tenant assertions")
class TenantIsolationRuntimeEvidenceIT {

    @Test
    @DisplayName("S4 skeleton - system module tenant-scoped path")
    void systemTenantPathSkeleton() {
        // TODO(S4): 选定 system-module 真实 Mapper/接口路径 + 两租户数据 + 断言隔离
    }
}
