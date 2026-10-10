package com.cloudinfra.test.support.diagnostics;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 容器运行时 <b>socket 连通性</b>取证 —— 补上宿主现场快照缺失的最后一环。
 *
 * <h2>为什么单独存在（2026-10-10 实测，见 AGENTS.md 第九课补记四十）</h2>
 *
 * 全量 IT 曾间歇性报出 {@code ContainerLaunchException}, 链尾是
 * {@code java.io.IOException: Broken pipe at UnixSocket$WrappedWritableByteChannel.write}。
 * 要归因它, 第一步必须是回答 <b>"Testcontainers 到底连的是哪个 socket"</b>, 而
 * {@link HostLoadDiagnosticsExtension} 的快照只给宿主账本(load/meminfo), <b>不含 socket 信息</b>。
 *
 * <p>本轮实测结论（关键）:
 * <table border="1">
 *   <caption>两个候选 socket 的实测结果</caption>
 *   <tr><th>候选</th><th>实测</th></tr>
 *   <tr><td>{@code $DOCKER_HOST} = {@code /run/podman/podman.sock}</td>
 *       <td><b>连不上</b> —— {@code BindException: Permission denied}（{@code drwx------ root root}）</td></tr>
 *   <tr><td>{@code $XDG_RUNTIME_DIR/podman/podman.sock}</td>
 *       <td><b>实际走这条</b> —— {@code RootlessDockerClientProviderStrategy} 只认 {@code XDG_RUNTIME_DIR}</td></tr>
 * </table>
 *
 * <p>⇒ 在本机上, 若 Testcontainers 回退到默认策略, 它<b>一个容器都起不来</b>。
 * 所以必须把"当前用的是哪个 socket / 此刻连得上吗"变成快照里的<b>一行事实</b>。
 *
 * <h2>三条刻意的设计约束（每条都来自一次真实的探坑）</h2>
 *
 * <ol>
 *   <li><b>不使用 {@code exists()} 判定存在性</b>（第五课）。
 *       {@code Path.exists()} 会把 {@code AccessDeniedException} <b>吞成 {@code false}</b>,
 *       于是"无权限"与"不存在"被压成同一个结论 —— 本机 {@code /run/podman/podman.sock}
 *       正是这种情形, 会导致"该 socket 不存在"的错误结论。
 *       本类改用取<b>失败模式</b>, 两句互斥: {@code NoSuchFileException} → 真不存在,
 *       {@code AccessDeniedException} → 存在但无权。</li>
 *   <li><b>不判断"是不是 socket", 只报 connect 的互斥结果</b>。
 *       真实 API 只有 {@link BasicFileAttributes#isOther()}（{@code UnixFileAttributes}
 *       是 {@code sun.nio.fs} 内部类, 不可用）。本机实测四种路径的 connect 结果<b>各不相同</b>:
 *       <pre>
 *         真实 socket(可连)      -&gt; connected
 *         socket(root-only)     -&gt; BindException | Permission denied
 *         普通文件 / 目录        -&gt; ConnectException | Connection refused
 *         不存在的路径           -&gt; SocketException | No such file or directory
 *       </pre>
 *       这四种互斥结果本身就足以定位, <b>无需</b>再叠加一个脆弱的类型推断
 *       （本类第一版就写了 {@code isSocket()}, 编译失败 —— 按记忆写 API 本身就是第五课）。</li>
 *   <li><b>不读写任何字节</b>（第五课: 探针查错了对象）。
 *       一旦去解析 {@code /v1.41/...} 之类的版本路径, 版本一变解析就失败,
 *       失败会被读成"连不上", 而实际连接是通的。</li>
 * </ol>
 *
 * <h2>诊断绝不抛异常</h2>
 * 诊断自身失败绝不能影响测试结果 —— 故所有采集都包在 {@code try/catch(Throwable)} 内,
 * 失败时输出明确标注的"未取证"字符串, 而不是抛出或显示成合法值
 * （第五课补记五: 探针失效伪装成合法值）。
 */
final class ContainerRuntimeSocketProbe {

    private ContainerRuntimeSocketProbe() {
    }

    /** Testcontainers 默认策略会走 {@code $DOCKER_HOST} —— 在本机它连不上, 故必须显式固定 rootless。 */
    private static final String STRATEGY_HINT =
        "RootlessDockerClientProviderStrategy(只认 $XDG_RUNTIME_DIR)";

    /** 用真实环境变量生成快照片段。诊断自身失败绝不影响测试结果。 */
    static String render() {
        return renderFrom(readEnvOrNull("DOCKER_HOST"), readEnvOrNull("XDG_RUNTIME_DIR"));
    }

    /**
     * 可注入的渲染入口 —— 供守护测试验证退化输入。
     *
     * <p>⚠️ <b>不提供无参的"猜环境"分支给测试</b>: 若只能读本机环境,
     * 测试就只能在"本机 socket 恰好可连"时通过 —— 那是<b>环境事实</b>, 不是被测行为。
     * 参数化后, "XDG 缺失""DOCKER_HOST 非法"等分支才真正可被断言。
     */
    static String renderFrom(String dockerHost, String xdg) {
        StringBuilder out = new StringBuilder();
        out.append("\n").append("-".repeat(72)).append('\n');
        out.append("---- 容器运行时 socket 连通性 ----\n");
        try {
            out.append("DOCKER_HOST     : ").append(orUnset(dockerHost)).append('\n');
            out.append("XDG_RUNTIME_DIR : ").append(orUnset(xdg)).append('\n');
            out.append("预期策略        : ").append(STRATEGY_HINT).append('\n');

            // A) 策略真正使用的 socket —— 这是"Testcontainers 实际连哪里"的答案
            if (xdg == null || xdg.isBlank()) {
                out.append("实际使用的 socket: (XDG_RUNTIME_DIR 未设置 ⇒ rootless 策略会直接失败)\n");
            } else {
                Path actual = Path.of(xdg, "podman", "podman.sock");
                out.append("实际使用的 socket: ").append(actual).append('\n');
                out.append("  属性   : ").append(describeAttributes(actual)).append('\n');
                out.append("  连接   : ").append(describeConnect(actual)).append('\n');
            }

            // B) DOCKER_HOST 指向的 socket —— 单独记录, 因为它在本机【连不上】。
            //    不作为错误, 只作事实: 回退到默认策略的改动会撞上它。
            if (dockerHost != null && dockerHost.startsWith("unix://")) {
                Path dh = Path.of(dockerHost.substring("unix://".length()));
                out.append("DOCKER_HOST 的 socket: ").append(dh).append('\n');
                out.append("  属性   : ").append(describeAttributes(dh)).append('\n');
                out.append("  连接   : ").append(describeConnect(dh)).append('\n');
                out.append("  ⇒ 默认策略会走这条; 本机它不可连, 故策略必须显式固定为 rootless\n");
            }
            out.append("· 本段只给【事实】, 不给结论: socket 可连 ≠ 运行时健康"
                + "(容器被杀 / SDK 内部错误 / Broken pipe 均不在此列)\n");
        } catch (Throwable t) {
            out.append("(未取证: socket 采集自身失败 -> ").append(t.getClass().getSimpleName()).append(")\n");
        }
        out.append("-".repeat(72)).append('\n');
        return out.toString();
    }

    /**
     * 取文件属性, 但<b>用失败模式而不是"是否存在"</b>来报告。
     *
     * <p>⚠️ 绝不调用 {@code Files.exists()} / {@code Path.exists()}: 它把
     * {@code AccessDeniedException} 报成 {@code false}, 于是
     * {@code /run/podman/podman.sock}（本机 root-only）会被读成"不存在"。
     *
     * <p>{@code isOther=true} 是 socket 的特征（普通文件为 {@code isRegularFile=true},
     * 目录为 {@code isDirectory=true}），但这只是<b>辅助信息</b>:
     * 决定性判据是 {@link #describeConnect} 的互斥结果。
     */
    static String describeAttributes(Path path) {
        try {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
            return "isOther=" + a.isOther() + " isRegularFile=" + a.isRegularFile()
                + " isDirectory=" + a.isDirectory() + " (isOther 是 socket 的特征)";
        } catch (java.nio.file.AccessDeniedException e) {
            return "denied —— 路径【存在】, 但当前用户无权访问（**不是\"不存在\"**）";
        } catch (java.nio.file.NoSuchFileException e) {
            return "missing —— 真的不存在";
        } catch (IOException e) {
            return "unknown (" + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * 真正建一次连接 —— 唯一有决定性的判据。
     *
     * <p>本机实测四种互斥结果（见类注释约束 2）, 足以区分"可连 / 无权限 / 不是 socket / 不存在"。
     *
     * <p>⚠️ 只看能否建连, <b>不读写任何字节</b>: 解析协议响应会引入版本依赖,
     * 版本变化时会把"连接正常"误报成"连不上"（第五课）。
     */
    static String describeConnect(Path path) {
        try (SocketChannel ch = SocketChannel.open(UnixDomainSocketAddress.of(path))) {
            // open(UnixDomainSocketAddress) 即完成 connect; 未抛异常即连接成功
            return "成功 (connected)";
        } catch (IOException | RuntimeException e) {
            // ⚠️ Permission denied / Connection refused / No such file 是三种互斥结论,
            //    必须原样保留异常类型与消息 —— 归一化成同一个"失败"就等于第五课。
            String msg = e.getMessage();
            return "失败: " + e.getClass().getSimpleName() + (msg == null ? "" : " | " + msg);
        }
    }

    private static String readEnvOrNull(String name) {
        try {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? null : v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String orUnset(String v) {
        return v == null ? "(未设置)" : v;
    }
}
