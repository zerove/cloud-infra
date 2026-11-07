package com.cloudinfrait.hostdiag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * {@code HostLoadDiagnosticsExtension} 类级兜底的<b>行为级</b>守护。
 *
 * <p><b>为什么需要它</b>: 2026-10-17 复核发现该扩展对<b>每个全绿测试类</b>都打印
 * 「容器启动失败 · 宿主现场快照」（b2-modules.log 55 次 / fulltest.log 76 次,
 * 连 12/12 全绿的 {@code GrpcSecurityInterceptorTest} 也打印）,
 * 而当时两个守护 ({@link ClassLevelFallbackTest} / {@link HostLoadDiagnosticsExtensionTest})
 * 全部通过 —— 前者只做源码字符串匹配, 后者只驱动过滤与渲染,
 * 二者<b>都没有让扩展在真实 JUnit 引擎里跑一遍生命周期</b>。⇒ 机制存在 ≠ 生效。
 *
 * <p><b>做法</b>: 用 JUnit Platform Launcher 在真实引擎里执行四个夹具类, 捕获 stderr,
 * 断言扩展在四种生命周期下的输出契约:
 * <ol>
 *   <li>全绿类 ⇒ 零快照（修复的缺陷本身）</li>
 *   <li>类级 {@code @BeforeAll} 失败 ⇒ 仍打印（防止把兜底修没）</li>
 *   <li>方法级容器失败 ⇒ 恰好一份（防重复打印）</li>
 *   <li>只含 {@code @Nested} 的外层类 ⇒ 零快照（钉住父链遍历）</li>
 * </ol>
 *
 * <p><b>负向对照</b>: 逐项改坏生产实现确认对应断言报红, 改回即绿 ——
 * 未做负向对照的守护与没有守护等价。
 */
class ClassLevelFallbackBehaviorTest {

    /** 与扩展输出的诊断标记保持字面一致 —— 改一处必须改两处。 */
    private static final String SNAPSHOT_MARKER = "容器启动失败 · 宿主现场快照";

    @Test
    @DisplayName("全绿类零快照")
    void 全绿类不得打印快照() {
        Run run = runFixtures(HostDiagGreenFixture.class);
        // 先证明夹具真跑了 —— 否则"没有输出"可能只是"探针没运行", 两者必须可区分
        assertEquals(1, run.summary().getTestsSucceededCount(),
            "夹具未真正执行 ⇒ 本判据无效(探针失效不得读成无发现)");
        assertFalse(run.stderr().contains(SNAPSHOT_MARKER),
            "全绿类打印了快照 ⇒ 2026-10-18 修复的假快照缺陷复发");
    }

    @Test
    @DisplayName("类级 @BeforeAll 失败: 兜底仍须打印(不得过度修复)")
    void 类级失败仍须打印() {
        Run run = runFixtures(HostDiagClassLevelFailureFixture.class);
        assertTrue(run.summary().getTestsFoundCount() >= 1,
            "夹具未被发现 ⇒ 探针失效, 本判据无效");
        assertTrue(run.summary().getTotalFailureCount() >= 1,
            "夹具未按预期失败 ⇒ 类级异常被吞掉了?");
        assertTrue(run.stderr().contains(SNAPSHOT_MARKER),
            "类级失败时零输出 ⇒ 2026-10-09 加入的兜底被修没了(过度修复)");
        assertTrue(run.stderr().contains("#<class-level>"),
            "兜底输出标识不对, 无法与方法级失败区分");
    }

    @Test
    @DisplayName("方法级容器失败: 恰好一份快照(emit 与兜底不得双打)")
    void 方法级失败恰好一份快照() {
        Run run = runFixtures(HostDiagMethodContainerFailureFixture.class);
        assertEquals(1, run.summary().getTestsFailedCount(), "夹具未按预期失败 ⇒ 探针失效");
        assertTrue(run.stderr().contains(
                "HostDiagMethodContainerFailureFixture#failsLikeAContainerStartup"),
            "方法级容器失败未打印快照 ⇒ testFailed 过滤或去重坏了");
        assertEquals(1, countOccurrences(run.stderr(), SNAPSHOT_MARKER),
            "快照份数 != 1 ⇒ afterAll 兜底在方法级已打印后重复打印(去重失效)");
    }

    @Test
    @DisplayName("只含 @Nested 的外层类零快照")
    void 嵌套外层类零快照() {
        Run run = runFixtures(HostDiagNestedOnlyFixture.class);
        assertEquals(1, run.summary().getTestsSucceededCount(),
            "嵌套夹具未真正执行 ⇒ 本判据无效");
        assertFalse(run.stderr().contains(SNAPSHOT_MARKER),
            "外层类被误判为类级失败 ⇒ markHadResult 未沿父链标记(或标记丢失)");
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    /**
     * 在真实引擎里执行夹具, 捕获 stderr。
     *
     * <p>⚠️ System.err 被临时替换 —— <b>必须还原原值</b>（finally 中恢复）:
     * 同一 JVM 内后续测试依赖原始 stderr。本仓在全局状态清理上踩过同类坑
     * (AGENTS.md 第 5 节「系统属性清理必须还原」)。
     */
    private static Run runFixtures(Class<?>... fixtures) {
        DiscoverySelector[] selectors = new DiscoverySelector[fixtures.length];
        for (int i = 0; i < fixtures.length; i++) {
            selectors[i] = DiscoverySelectors.selectClass(fixtures[i]);
        }
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectors)
            .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();

        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            launcher.execute(request, listener);
        } finally {
            System.setErr(original);
        }
        return new Run(listener.getSummary(), captured.toString(StandardCharsets.UTF_8));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0;
                i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /** Launcher 一次执行的产物: 摘要 + 捕获到的 stderr。 */
    private record Run(TestExecutionSummary summary, String stderr) {
    }
}
