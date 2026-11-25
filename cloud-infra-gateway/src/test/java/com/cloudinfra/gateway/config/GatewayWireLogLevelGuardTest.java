package com.cloudinfra.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 {@code logback-spring.xml} 里 HttpClient5 的报文日志级别。
 *
 * <p><b>为什么需要守护</b>: 2026-10-06 实测缺陷 —— 网关的 logback 配了
 * {@code <logger name="org.apache.hc.client5" level="DEBUG"/>}，
 * 使 HttpClient5 的 wire logger 把<b>整个 HTTP 报文（含请求体）逐字节</b>
 * 喂给 logback 的 {@code PatternLayout}。实测 OOM 堆栈为:
 * <pre>
 * StreamUtils.copy → LoggingOutputStream.write → Wire.output
 *   → Wire.wire → Logger.debug → PatternLayout.doLayout
 *     → StringBuilder.append → Arrays.copyOf → OutOfMemoryError
 * </pre>
 * 即每个 body 字节被扩成一个 char(2B)，叠加 StringBuilder 扩容拷贝，
 * 约 37 倍放大。后果是<b>经网关上传超过 6MB 的文件必然打崩网关</b>
 * （干净基线逐级实测 4MB/6MB 成功、7MB/8MB 必崩），
 * 且 OOM 后 {@code PoolingHttpClientConnectionManager} 进入半关闭态，
 * 报 {@code Connection pool shut down}，此后<b>所有</b>请求（含登录）返回 500。
 *
 * <p><b>为什么这类缺陷必须静态守护</b>: 它不会让任何单测变红。
 * 组件自测全绿，而真实容器里 7MB 上传必崩 —— 正是本项目记录在
 * AGENTS.md 的"机制存在 ≠ 机制在运行"同一类。
 *
 * <p><b>判定为何这样写</b>: 按<b>最长前缀匹配</b>还原 logback 的真实生效级别，
 * 而不是只查有没有某一行。这样即使有人把
 * {@code <logger name="org.apache.hc.client5" level="DEBUG"/>}
 * 加回来（它会覆盖更具体的子 logger 配置），本测试仍然报红。
 * 只检查"wire 那行是不是 INFO"是不够的 —— 上游可以覆盖它。
 */
class GatewayWireLogLevelGuardTest {

    private static final Path LOGBACK = Path.of("src/main/resources/logback-spring.xml");

    /**
     * HttpClient5 的报文日志包。这些包把请求/响应<b>逐字节</b>写进日志，
     * 开 DEBUG 即等于把整个 body 过一遍 StringBuilder。
     */
    private static final List<String> WIRE_LOGGERS = List.of(
            "org.apache.hc.client5.http.wire",
            "org.apache.hc.client5.http.headers");

    @Test
    @DisplayName("HttpClient5 报文日志不得为 DEBUG/TRACE —— 会把整个 body 逐字节拼成字符串")
    void wireLoggersMustNotBeDebugOrTrace() throws Exception {
        List<Element> loggerElements = parseLoggerElements();
        assertThat(loggerElements)
                .as("守护前提: logback-spring.xml 至少含一个 <logger> 元素。"
                        + "若解析结果为空, 说明文件结构变了或解析器坏了 —— "
                        + "此时本测试必须报红而不是静默通过")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (String pkg : WIRE_LOGGERS) {
            String effective = effectiveLevel(loggerElements, pkg);
            if (effective == null) {
                // 未显式配置时继承 root/上级; root 是 INFO, 安全。
                // 但显式声明更稳妥 —— 一旦有人给 org.apache.hc.client5 开 DEBUG,
                // 下面那条测试会拦住, 这里保持"未配置"即可。
                continue;
            }
            if ("DEBUG".equalsIgnoreCase(effective) || "TRACE".equalsIgnoreCase(effective)) {
                violations.add(pkg + " 的生效级别为 " + effective
                        + " (DEBUG/TRACE 会把 HTTP 报文逐字节过 StringBuilder, "
                        + "实测约 37 倍内存放大, 7MB 上传即打崩 -Xmx256m 的网关)");
            }
        }
        assertThat(violations)
                .as("HttpClient5 报文日志级别回归 —— 这正是 2026-10-06 网关 OOM 的真因")
                .isEmpty();
    }

    @Test
    @DisplayName("不得对 org.apache.hc.client5 整包开 DEBUG —— 它会覆盖更具体的子配置")
    void client5RootPackageMustNotBeDebug() throws Exception {
        List<Element> loggerElements = parseLoggerElements();
        List<String> violations = new ArrayList<>();
        for (Element e : loggerElements) {
            String name = attr(e, "name");
            String level = attr(e, "level");
            // 整包配置会覆盖所有子包, 使上面那条测试的"最具体优先"失效。
            if (name != null && level != null
                    && "org.apache.hc.client5".equals(name)
                    && ("DEBUG".equalsIgnoreCase(level) || "TRACE".equalsIgnoreCase(level))) {
                violations.add("<logger name=\"org.apache.hc.client5\" level=\"" + level
                        + "\"/> 会覆盖子包配置, 重新打开逐字节报文日志");
            }
        }
        assertThat(violations)
                .as("org.apache.hc.client5 整包 DEBUG 会覆盖 wire 的 INFO 设置")
                .isEmpty();
    }

    @Test
    @DisplayName("org.apache.hc.core5 与 client5 不是一回事 —— 不得连带关闭 core5 的调试日志")
    void core5MustRemainDistinguishable() throws Exception {
        // 本测试守护的是"修复时不要误伤": core5 的 wire 是连接级摘要, 无害,
        // 与 client5 的逐字节报文日志完全不是一回事。
        // 若有人把两者混为一谈而一并关掉, 说明他没读注释, 这里报红提醒。
        List<Element> loggerElements = parseLoggerElements();
        boolean core5Configured = loggerElements.stream()
                .anyMatch(e -> "org.apache.hc.core5".equals(attr(e, "name")));
        assertThat(core5Configured)
                .as("org.apache.hc.core5 应保留独立配置（连接级摘要日志, 无害）。"
                        + "它与 org.apache.hc.client5.http.wire（逐字节报文日志）不是一回事, "
                        + "若被一并删除, 说明混淆了两者")
                .isTrue();
    }

    /** 按 logback 的最长前缀匹配规则, 还原某包的实际生效级别。 */
    private static String effectiveLevel(List<Element> loggerElements, String targetPackage) {
        String best = null;
        int bestLen = -1;
        for (Element e : loggerElements) {
            String name = attr(e, "name");
            String level = attr(e, "level");
            if (name == null || level == null) {
                continue;
            }
            // logback 以 '.' 分段做前缀匹配: a.b.c 匹配包 a.b.c 及其子包
            if (targetPackage.equals(name) || targetPackage.startsWith(name + ".")
                    || name.startsWith(targetPackage + ".")) {
                if (name.length() > bestLen) {
                    bestLen = name.length();
                    best = level;
                }
            }
        }
        return best;
    }

    private static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name).trim() : null;
    }

    private static List<Element> parseLoggerElements() throws Exception {
        assertThat(Files.exists(LOGBACK))
                .as("找不到 %s —— 守护必须指向真实被加载的那份配置, 不能凭空判绿", LOGBACK)
                .isTrue();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc;
        try (InputStream in = Files.newInputStream(LOGBACK)) {
            doc = factory.newDocumentBuilder().parse(in);
        }
        NodeList nodes = doc.getElementsByTagName("logger");
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            result.add((Element) nodes.item(i));
        }
        return result;
    }
}
