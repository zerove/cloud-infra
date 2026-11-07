package com.cloudinfrait.hostdiag;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 外层类自身零测试、只含 {@code @Nested} 全绿子类的夹具。
 *
 * <p>验证契约: 外层类的 {@code afterAll} 也不得打印快照 —— 它"零直接方法结果"
 * 不是类级失败, 而是结果都产生在子类里。本夹具钉住
 * {@code markHadResult} 的父链遍历（不遍历父链时, 外层必被误判并打印）。
 */
class HostDiagNestedOnlyFixture {

    @Nested
    class InnerGreen {

        @Test
        void passes() {
        }
    }
}
