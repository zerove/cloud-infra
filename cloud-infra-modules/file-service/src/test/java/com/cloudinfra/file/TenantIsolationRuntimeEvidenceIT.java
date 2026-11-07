package com.cloudinfra.file;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S4 运行时证据骨架（file-service 多租户路径）。
 *
 * @see docs/adr/ADR-011-notification-history-logs-scope.md (流程参考, 非直接相关)
 */
@Disabled("S4 evidence skeleton: enable with real infra and cross-tenant assertions")
class TenantIsolationRuntimeEvidenceIT {

    @Test
    @DisplayName("S4 skeleton - file service tenant-scoped path")
    void fileTenantPathSkeleton() {
        // TODO(S4): 选定 file-service 真实读写路径 + 两租户数据 + 断言隔离
    }
}
