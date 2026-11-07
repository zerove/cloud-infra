package com.cloudinfrait.hostdiag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 故意在类级 {@code @BeforeAll} 抛异常的夹具 —— 模拟"类级容器启动失败":
 * 任何测试方法都没跑, 方法级 {@code TestWatcher} 回调不会触发,
 * 只剩 {@code afterAll} 兜底一条路可见。
 *
 * <p>验证契约: 这种情况扩展<b>必须仍打印</b>快照（2026-10-09 加入兜底的本意）。
 * 若某个"修复"让本夹具不再打印, 说明把真问题一起修没了（过度修复）。
 */
class HostDiagClassLevelFailureFixture {

    @BeforeAll
    static void classLevelSetupFails() {
        throw new IllegalStateException("simulated class-level container startup failure");
    }

    @Test
    void neverExecuted() {
    }
}
