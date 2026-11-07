package com.cloudinfrait.hostdiag;

import org.junit.jupiter.api.Test;

/**
 * 故意全绿的夹具 —— 供 {@link ClassLevelFallbackBehaviorTest} 用 Launcher 在真实引擎里执行。
 *
 * <p>验证契约: 扩展对<b>整类全绿</b>不得打印任何快照
 * （2026-10-18 修复前的缺陷正是对每个全绿类都打印）。
 *
 * <p>命名刻意不含 {@code Test} 后缀 —— surefire 不会直接执行它,
 * 它只出现在守护测试的 Launcher 运行里。
 */
class HostDiagGreenFixture {

    @Test
    void passes() {
    }
}
