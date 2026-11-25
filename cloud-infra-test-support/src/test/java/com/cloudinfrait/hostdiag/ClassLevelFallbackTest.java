package com.cloudinfrait.hostdiag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 类级容器失败兜底的守护测试。
 *
 * <p><b>为什么需要它</b>: 2026-10-09 真实 Ryuk 失败轮里, 诊断扩展<b>注册正确、
 * jar 正确、判据能匹配</b>, 却零输出。根因是失败发生在类级 {@code @Container} 启动阶段
 * （surefire XML 为 {@code <testcase name="">} + 1 个 {@code <error>}）,
 * 而 {@code TestWatcher.testFailed} 只在方法级上下文触发。
 *
 * <p><b>为什么上一版守护没抓到</b>: 旧守护只验"扩展能否被 ServiceLoader 发现",
 * 而"能被发现"与"在类级失败时被调用"是两个不同命题 —— 第三条硬规则。
 *
 * <p>⚠️ <b>本文件自身的历史教训</b>: 守护必须做<b>负向对照</b>才有意义。
 * 若把 {@code beforeAll} 钩子去掉, 下面 {@link #类级钩子被移除时守护必须报红()} 必须失败;
 * 未验证过这一点的守护, 与没有守护等价（补记十七: 11 项补扫测试全绿而缺陷仍在）。
 */
class ClassLevelFallbackTest {

    /**
     * 结构断言: 扩展必须实现 {@code BeforeAllCallback}, 否则类级路径无处挂载。
     *
     * <p>判据用<b>接口全名精确匹配</b>, 不用 {@code contains("BeforeAll")}
     * —— 后者会被注释或 import 里的同名字符串满足, 变成恒假绿
     * （补记四: {@code p.startsWith("com.cloudinfra")} 收窄后仍不报红）。
     */
    @Test
    @DisplayName("扩展必须实现 BeforeAllCallback 与 AfterAllCallback(声明行精确匹配)")
    void 扩展必须实现BeforeAllCallback() {
        String src = ExtensionSource.sourceOf("HostLoadDiagnosticsExtension");
        assertTrue(src.contains("import org.junit.jupiter.api.extension.AfterAllCallback"),
            "缺少 AfterAllCallback 的 import ⇒ 编译期即报 'method does not override'"
                + "(本次真实踩到: 写了 afterAll 方法却忘了在 implements 子句里声明接口)");
        String implLine = ExtensionSource.lineContaining(src, "public class HostLoadDiagnosticsExtension");
        assertFalse(implLine.isEmpty(), "读不到扩展类声明行, 探针失效");
        assertTrue(implLine.contains("BeforeAllCallback"),
            "扩展未实现 BeforeAllCallback ⇒ 类级容器失败时 testFailed 不触发, 快照零输出。"
                + " 实际声明: " + implLine);
        assertTrue(implLine.contains("AfterAllCallback"),
            "扩展未实现 AfterAllCallback ⇒ afterAll 里补打的类级快照永不执行。"
                + " 实际声明: " + implLine);
    }

    /** 钩子方法必须真的存在, 而不是在接口声明行里提一句。 */
    @Test
    @DisplayName("必须真的覆写 beforeAll/afterAll, 且往标记集合写入")
    void 类级钩子必须真的覆写() {
        String src = ExtensionSource.sourceOf("HostLoadDiagnosticsExtension");
        assertTrue(src.contains("public void beforeAll("),
            "缺少 beforeAll 覆写 ⇒ 无从记录'进过 beforeAll'");
        assertTrue(src.contains("public void afterAll("),
            "缺少 afterAll 覆写 ⇒ 无法在零方法结果时补打快照");
        assertTrue(src.contains("CLASS_MARKED.add("),
            "beforeAll 未写入标记 ⇒ afterAll 无法区分'类级失败'与'正常全绿'");
        assertTrue(src.contains("CLASS_MARKED.remove("),
            "afterAll 未清理标记 ⇒ 同一 JVM 内后续测试类会被误判为类级失败并刷出假快照");
    }

    /**
     * 负向对照: 移除钩子后守护必须报红。
     *
     * <p>本测试自身不能改生产源码(会让"守护有效"与"源码被改"混淆),
     * 故用一份<b>被刻意破坏的副本</b>验证判据仍有区分力。
     */
    @Test
    @DisplayName("负向对照: 破坏副本后判据必须报红(否则本守护是恒假绿)")
    void 类级钩子被移除时守护必须报红() {
        String src = ExtensionSource.sourceOf("HostLoadDiagnosticsExtension");
        // 只删【一个】接口 —— 若两个一起删, 判据无法区分是哪个生效
        String broken = src.replace(", AfterAllCallback", "");
        assertFalse(broken.equals(src), "破坏未生效 ⇒ 生产源码声明与预期不符, 判据已失效");
        assertFalse(broken.contains(", AfterAllCallback"),
            "破坏后仍含钩子, 判据无区分力");

        String implLine = ExtensionSource.lineContaining(broken,
            "public class HostLoadDiagnosticsExtension");
        assertFalse(implLine.contains("AfterAllCallback"),
            "判据未捕获钩子移除 ⇒ 本守护是恒假绿");
    }

    /**
     * 去重是必须的: 类级失败时 JUnit 会同时报 class-level error 与逐方法失败,
     * 不去重会打印 N+1 份相同快照, 把真正的日志淹掉(补记: 噪声即失效)。
     */
    @Test
    @DisplayName("必须去重, 否则一次失败刷 N+1 份快照")
    void 必须去重() {
        String src = ExtensionSource.sourceOf("HostLoadDiagnosticsExtension");
        assertTrue(src.contains("PRINTED.add("),
            "缺少 PRINTED.add 去重判据 ⇒ 类级失败会刷出 N+1 份相同快照");
    }

    /**
     * 兜底输出必须<b>标注异常未捕获</b>。
     *
     * <p>这是第五课的直接应用: 该分支拿不到异常对象, 若输出里不写明这一点,
     * 下一个人会以为"扩展连异常类型都判断不出来", 从而怀疑判据而非上下文。
     */
    @Test
    @DisplayName("兜底输出必须显式标注未捕获到异常(不得让读者误以为判据失效)")
    void 兜底输出必须标注未捕获异常() {
        String src = ExtensionSource.sourceOf("HostLoadDiagnosticsExtension");
        assertTrue(src.contains("未捕获到异常"),
            "类级兜底输出未标注'未捕获到异常' ⇒ 读者会误判为判据失效(第五课形态)");
        assertTrue(src.contains("class-level"),
            "类级兜底输出的上下文标识未含 class-level ⇒ 无法与真实方法级失败区分");
    }
}