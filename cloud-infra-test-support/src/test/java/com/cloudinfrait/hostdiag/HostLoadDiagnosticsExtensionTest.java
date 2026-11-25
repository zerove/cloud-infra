package com.cloudinfrait.hostdiag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 守护 {@code HostLoadDiagnosticsExtension} 的存在与正确性。
 *
 * <h2>⚠️ 这里踩了两个连续的坑, 都值得记下来</h2>
 *
 * <p><b>坑一: 守护自己假绿</b>。第一版用
 * {@code try { doomed.start(); } catch (ContainerLaunchException e) { ... }}
 * 把预期异常吃掉, 于是测试**通过** —— 而"通过"只证明容器如期超时,
 * <b>完全没证明扩展被加载</b>。扩展从未注册它也照样绿。
 *
 * <p><b>坑二: 修好坑一后, 守护正确报红, 但原因不是"扩展坏了"</b>。
 * 第二版捕获 {@code System.err} 后断言诊断标记出现, 报红说"扩展未触发"。
 * 真实原因是: {@link org.junit.jupiter.api.extension.TestWatcher#testFailed}
 * <b>只在测试失败时调用</b>, 而第二版仍然 catch 了异常 ⇒ 测试通过 ⇒
 * {@code testFailed} 永不触发。
 * ⇒ <b>扩展逻辑完全正确, 是我的验证方式让它无法被触发。</b>
 *
 * <p><b>最终结构</b>: 把"扩展能否在真实失败中被触发"与"扩展逻辑是否正确"
 * 拆成两件事, 因为前者需要一个**故意失败**的测试(会污染构建), 不能当常规守护。
 * <ul>
 *   <li>本类(永远绿): 直接驱动扩展的过滤与渲染逻辑, 逐项断言输出内容</li>
 *   <li>{@code #ignored} 之外的 IT: 真实失败时由扩展自动打印, 见 AGENTS.md 补记十六</li>
 * </ul>
 */
class HostLoadDiagnosticsExtensionTest {

    /** 扩展的诊断标记 —— 与被守护对象保持字面一致, 改一处必须改两处(见下方守护) */
    private static final String DIAG_MARKER = "容器启动失败 · 宿主现场快照";

    private static final String EXT_CLASS =
        "com.cloudinfra.test.support.diagnostics.HostLoadDiagnosticsExtension";

    // ── 判据 1: 关键词过滤 —— 容器相关才触发, 否则噪声淹没信号 ──────────────

    @Test
    void filtersInContainerRelatedFailures() throws Exception {
        assertTrue(invokeFilter("ContainerLaunchException: Timed out waiting for log output"),
            "容器启动超时必须被识别为容器相关");
        assertTrue(invokeFilter("InternalServerErrorException: Status 500 {\"cause\":\"no such container\"}"),
            "no such container(机制一) 必须被识别");
        assertTrue(invokeFilter("DockerException: CreateContainerError"),
            "Docker API 失败必须被识别");
    }

    @Test
    void ignoresNonContainerFailures() throws Exception {
        assertFalse(invokeFilter("AssertionFailedError: expected: <200> but was: <500>"),
            "纯业务断言失败不应触发诊断(否则噪声淹没信号)");
        assertFalse(invokeFilter("org.springframework.web.client.HttpServerErrorException: 500"),
            "业务 HTTP 失败不应触发");
        assertFalse(invokeFilter("java.lang.NullPointerException"),
            "NPE 不应触发");
    }

    // ── 判据 2: 渲染内容 —— 必须含真实读到的字段, 不是占位符 ────────────────

    @Test
    void renderedSnapshotContainsRealHostFacts() throws Exception {
        Method render = extensionClass().getDeclaredMethod(
            "renderSnapshot", String.class, String.class);
        render.setAccessible(true);

        String out = (String) render.invoke(null,
            "com.example.SomeIT#someTest",
            "ContainerLaunchException: Timed out waiting for log output matching '.*started.*'");

        // 真实读取的字段(来自 /proc, 非硬编码)
        assertTrue(out.contains("load(1/5/15min)"), "缺少 load 字段");
        assertTrue(out.contains("CPU 核数"), "缺少 CPU 核数");
        assertTrue(out.contains("Committed_AS"), "缺少 Committed_AS");
        assertTrue(out.contains("CommitLimit"), "缺少 CommitLimit");
        assertTrue(out.contains("MemAvailable"), "缺少 MemAvailable");
        assertTrue(out.contains(DIAG_MARKER), "缺少诊断标记");

        // ⚠️ 这里必须防住【硬编码 0】: 负向对照实测过, 把 readLoadavg() 改成
        //    return "0.00 0.00 0.00" 时, 单纯匹配 \d+\.\d+ 依然通过 —— 即守护假绿。
        //    第五课形态: 探针失效伪装成"读到了一个合法但无意义的值"。
        //    真实 /proc/loadavg 第一段永远 > 0(有进程就在跑), 故断言 > 0。
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("load\\(1/5/15min\\) : ([0-9.]+)").matcher(out);
        assertTrue(m.find(), "load 字段格式不对: " + out);
        double load1 = Double.parseDouble(m.group(1));
        assertTrue(load1 > 0.0,
            "load(1min) 读到了 " + load1 + " —— 必然是读取失败后回落的默认值, "
                + "不能显示为合法的 0(探针失效必须显式标注, 第五课补记五)");

        // ⚠️ 不能断言"恰好三段": /proc/loadavg 实际是五段
        //    (三段负载 + "运行中/总进程数" + PID)。实测: "13.00 14.53 15.01 5/4050 1641671"。
        //    我第一版按三段断言 → 守护误报红, 属于【判据写错】而非【实现有问题】。
        //    这里只断言前三段存在且可解析, 不断言总段数。
        Method readLoad = extensionClass().getDeclaredMethod("readLoadavg");
        readLoad.setAccessible(true);
        String raw = (String) readLoad.invoke(null);
        String[] parts = raw.trim().split("\\s+");
        assertTrue(parts.length >= 3,
            "loadavg 前三段缺失, 实际: \"" + raw + "\"");
        for (int i = 0; i < 3; i++) {
            Double.parseDouble(parts[i]);   // 不可解析会抛 NumberFormatException, 即判红
        }

        // 已知的判断边界必须写进输出, 避免下一个人重复踩坑
        assertTrue(out.contains("≠"), "未写明 Committed_AS 超限不等于分配失败");

        // ⚠️ 负向对照实测: 把 gb() 退回 `String.format("%.2f GB", kb/…)` 时守护仍绿,
        //    因为真实 /proc/meminfo 永远读得到 ⇒ 失效分支根本没被覆盖。
        //    这是"没验到 ≠ 验过了": 判据只跑了成功路径, 探针失效路径无人看守。
        //    正解: 直接对失效哨兵 -1 断言, 把未覆盖的分支钉住。
        Method gb = extensionClass().getDeclaredMethod("gb", long.class);
        gb.setAccessible(true);
        assertEquals("(读取失败)", gb.invoke(null, -1L),
            "探针失效(-1)必须显式标注, 不能渲染成 \"-0.00 GB\" —— "
                + "第五课补记五: 被丢弃的值会伪装成合法值");
        assertTrue(((String) gb.invoke(null, 1024L * 1024L)).endsWith("1.00 GB"),
            "正常值渲染错误");

        // 判读必须【给判据不给结论】: 写死"load 高⇒慢"会把未证明的假设固化。
        // 负向对照: 恢复旧文案即变红。
        assertTrue(out.contains("load1/核数"), "判读缺少 load/核数 比值判据");
        assertTrue(out.contains("尚需受控对照定性"),
            "判读把未受控证明的假设写成了结论 —— 高 load 与失败的相关性尚未定性");
        assertFalse(out.contains("远高于核数 ⇒ 大概率"),
            "判读仍用预断式措辞, 会让下一个人拿未证明的假设当已证事实");
    }

    @Test
    void snapshotHandlesUnreadableProcWithoutThrowing() throws Exception {
        // 探针失效必须显式标注, 不能伪装成"读到 0"(第八课: 探针失效不得伪装成无发现)
        Method render = extensionClass().getDeclaredMethod(
            "renderSnapshot", String.class, String.class);
        render.setAccessible(true);
        String out = (String) render.invoke(null, "T#m", "ContainerLaunchException: x");
        assertTrue(out != null && !out.isBlank(), "渲染结果不得为 null/空");
    }

    // ── 判据 3: 扩展确实被 auto-detection 注册(结构层) ──────────────────────

    @Test
    void autodetectionIsEnabledInResources() throws Exception {
        // 判据必须指向【被检对象本身】: 不是"文件存在", 而是"开关为 true"
        boolean found = false;
        try (var in = getClass().getClassLoader()
                .getResourceAsStream("junit-platform.properties")) {
            assertTrue(in != null, "classpath 上找不到 junit-platform.properties");
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : content.split("\\R")) {
                String t = line.trim();
                if (t.startsWith("junit.jupiter.extensions.autodetection.enabled")) {
                    assertTrue(t.endsWith("true"),
                        "auto-detection 开关未设为 true: " + t);
                    found = true;
                }
            }
        }
        assertTrue(found, "properties 里没有 auto-detection 开关 —— 扩展不会被加载");
    }

    // ── 判据 4: ServiceLoader 注册必须真的能被枚举到 ────────────────────────
    //
    // ⚠️ 这是本轮最贵的教训: 扩展逻辑与 properties 全对, 守护测试也全绿,
    //    但真实下游模块里探针故意失败时诊断块【不出现】。
    //    根因: 当时只建了源目录下的 services 文件且扩展仍在编译中,
    //    而我读的是"上一次成功构建留下的旧 jar" ⇒ 探针跑的是没有注册的旧版本。
    //    ⇒ 判据必须走 ServiceLoader 【实际枚举】, 而不是"文件存在"。
    //
    // ⚠️ 关于本判据的边界(诚实记录): 它跑在 target/classes 上,
    //    能证明"注册路径通", **不能**证明"打进 jar 后仍在"。
    //    后者由 notification-service 的一次性接线探针验证过(人工, 不可回归)。
    //    若要把它变成可回归门禁, 需要新增 IT 级断言, 超出本守护范围。

    @Test
    void serviceLoaderRegistrationIsDiscoverable() throws Exception {
        // (a) 源目录存在 —— 便宜的早期检查
        java.net.URL svc = getClass().getClassLoader()
            .getResource("META-INF/services/org.junit.jupiter.api.extension.Extension");
        assertTrue(svc != null, "缺少 META-INF/services/org.junit.jupiter.api.extension.Extension");
        String content;
        try (var in = svc.openStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(content.contains(EXT_CLASS),
            "services 文件未声明本扩展, 实际内容: \"" + content.trim() + "\"");

        // (b) ServiceLoader 真能枚举到 —— 这正是引擎 registerAutoDetectedExtensions 走的路径
        java.util.ServiceLoader<org.junit.jupiter.api.extension.Extension> sl =
            java.util.ServiceLoader.load(org.junit.jupiter.api.extension.Extension.class,
                getClass().getClassLoader());
        boolean found = sl.stream()
            .anyMatch(p -> EXT_CLASS.equals(p.type().getName()));
        assertTrue(found,
            "ServiceLoader 枚举不到本扩展 ⇒ 引擎的 registerAutoDetectedExtensions "
                + "拿不到它 ⇒ 真实 IT 失败时不会打印诊断");
    }

    // ── 判据 5: JUnit 6 没有 @AutoDetect, 不得凭记忆写 ───────────────────────
    //
    // 本轮我按记忆加 @AutoDetect, 编译报 cannot find symbol。
    // 解包 junit-jupiter-api 确认: extension 包下【不存在】该注解。
    // ⇒ 判据不是"代码能编译"(编译已挡住), 而是把这个事实写下来防止再犯。
    @Test
    void junitApiExposesNoAutoDetectAnnotation() throws Exception {
        boolean exists;
        try {
            Class.forName("org.junit.jupiter.api.extension.AutoDetect");
            exists = true;
        } catch (ClassNotFoundException expected) {
            exists = false;
        }
        assertFalse(exists,
            "当前 JUnit 竟有 @AutoDetect? 若升级了 JUnit 请同步更新本扩展的注册方式与本判据");
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private static Class<?> extensionClass() throws ClassNotFoundException {
        return Class.forName(EXT_CLASS);
    }

    private static boolean invokeFilter(String chain) throws Exception {
        Method m = extensionClass().getDeclaredMethod("looksLikeContainerFailure", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, chain);
    }
}
