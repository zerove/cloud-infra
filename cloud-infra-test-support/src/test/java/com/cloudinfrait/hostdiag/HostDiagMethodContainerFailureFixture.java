package com.cloudinfrait.hostdiag;

import org.junit.jupiter.api.Test;

/**
 * 故意在方法级抛"容器相关"异常的夹具 —— 走 {@code testFailed} → {@code emit} 路径。
 *
 * <p>验证契约: 恰好打印<b>一份</b>快照 —— 方法级已打印, {@code afterAll} 兜底
 * 不得再打第二份（去重契约）。
 */
class HostDiagMethodContainerFailureFixture {

    @Test
    void failsLikeAContainerStartup() {
        throw new IllegalStateException(
            "ContainerLaunchException: Timed out waiting for log output matching '.*started.*'");
    }
}
