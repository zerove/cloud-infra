package com.cloudinfra.test.support.diagnostics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@link ContainerRuntimeSocketProbe} 的守护测试。
 *
 * <h2>为什么放同包而不是 {@code com.cloudinfrait.*}</h2>
 * 被测方法是包私有（{@code renderFrom} / {@code describeAttributes} / {@code describeConnect}）。
 * 若测试放到别的包, 就得用反射 —— 而反射版第一版已经踩过:
 * 它<b>既</b>用了反射<b>又</b>放在 {@code com.cloudinfrait} 包, 与"测试夹具须隔离"的初衷自相矛盾。
 * 同包直调是最诚实的形态: 它明确承认"这两个方法本来就只服务于本探针"。
 *
 * <h2>三条断言各自对应的真实教训</h2>
 * <ol>
 *   <li>{@code connect} 的多种失败结论必须<b>互不相同</b> —— 若归一化成同一个"失败",
 *       {@code Permission denied}（无权限）与 {@code No such file}（不存在）就再也分不开,
 *       而这两者处置完全相反（第五课）。</li>
 *   <li>{@code renderFrom} 必须在<b>没有可用 socket</b>时如实标注, 不能沉默给空段。</li>
 *   <li>{@code renderFrom} 永不抛异常 —— 诊断自身失败绝不能影响测试结果。</li>
 * </ol>
 *
 * <h2>刻意不做的断言</h2>
 * <ul>
 *   <li><b>不断言"本机某个 socket 连得上"</b> —— 那是环境事实, 换台机器就失效。
 *       需要真实 socket 的用例改为<b>由本进程创建</b>, 与宿主环境无关。</li>
 *   <li><b>不做源码字符串守护</b> —— 它在 cwd 不对时会"找不到文件"而静默通过,
 *       变成只在全绿时才检查的假门禁（补记三十五）。改为直接验证行为。</li>
 * </ul>
 */
class ContainerRuntimeSocketProbeTest {

    @Test
    void connectDistinguishesFileFromMissingPath() throws IOException {
        Path regular = Files.createTempFile("probe-seltest", ".txt");
        try {
            String onFile = ContainerRuntimeSocketProbe.describeConnect(regular);
            String onMissing = ContainerRuntimeSocketProbe.describeConnect(
                Path.of("/nonexistent-probe-seltest/x.sock"));

            assertFalse(onFile.equals(onMissing),
                "普通文件与不存在路径的结论被归一化了 —— 两种互斥结论压成同一输出");

            // 两种失败都必须【保留原因】, 不能只剩一个"失败"
            assertTrue(onFile.contains("失败") && onFile.contains("|"),
                "失败结论必须携带原因, 实际: " + onFile);
            assertTrue(onMissing.contains("失败") && onMissing.contains("|"),
                "失败结论必须携带原因, 实际: " + onMissing);
        } finally {
            Files.deleteIfExists(regular);
        }
    }

    @Test
    void connectSucceedsOnRealUnixSocketCreatedByThisProcess() throws IOException {
        // 由本进程当服务端 ⇒ 与宿主环境无关, 换台机器同样成立。
        // ⚠️ 刻意【不】用 ServerSocketChannel.open(UnixDomainSocketAddress) ——
        //    该重载不存在(只接受 InetSocketAddress), 第一版就是这么编译失败的;
        //    也不写 new UnixDomainSocketAddress(Path) —— 构造器是私有的, 必须用 of(...)。
        Path sock = Path.of(System.getProperty("java.io.tmpdir"),
            "probe-seltest-" + System.nanoTime() + ".sock");
        Files.deleteIfExists(sock);
        try (ServerSocketChannel server =
                 ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(sock));
            String result = ContainerRuntimeSocketProbe.describeConnect(sock);
            assertTrue(result.contains("成功"),
                "本进程创建的 unix socket 应连得上, 实际: " + result);
        } finally {
            Files.deleteIfExists(sock);
        }
    }

    @Test
    void attributesDistinguishMissingFromExisting() throws IOException {
        // Path.exists() 会把 AccessDeniedException 吞成 false(第五课),
        // 所以本探针用"失败模式"区分二者。此断言直接锁住该行为。
        String onMissing = ContainerRuntimeSocketProbe.describeAttributes(
            Path.of("/nonexistent-probe-seltest/x.sock"));
        assertTrue(onMissing.contains("missing"),
            "不存在的路径必须报 missing, 实际: " + onMissing);

        // 对一个【确实存在】的路径, 不得报 missing。temp 文件由本测试创建, 必然可读,
        // 因此不依赖 cwd 下的源码文件是否存在(那会让用例在 IDE 里假绿)。
        Path self = Files.createTempFile("probe-seltest-attr", ".txt");
        try {
            String onFile = ContainerRuntimeSocketProbe.describeAttributes(self);
            assertFalse(onFile.contains("missing"),
                "存在的文件不得被报成 missing, 实际: " + onFile);
            assertTrue(onFile.contains("isRegularFile=true"),
                "普通文件应以 isRegularFile 标识, 实际: " + onFile);
        } finally {
            Files.deleteIfExists(self);
        }
    }

    @Test
    void renderReportsActualSocketAndStrategy() {
        String out = ContainerRuntimeSocketProbe.renderFrom(
            "unix:///run/podman/podman.sock", "/run/user/1000");

        assertTrue(out.contains("容器运行时 socket 连通性"), "缺少段落标题");
        assertTrue(out.contains("/run/user/1000/podman/podman.sock"),
            "必须打印【策略实际使用的】socket, 而不只是 DOCKER_HOST —— "
                + "本机 DOCKER_HOST 指向的 socket 连不上, 只报它会误导:\n" + out);
        assertTrue(out.contains("RootlessDockerClientProviderStrategy"),
            "缺少策略说明 —— 下一个人会重新考古\"Testcontainers 连的是哪个 socket\"");
        assertTrue(out.contains("/run/podman/podman.sock"),
            "必须同时报告 DOCKER_HOST 指向的 socket（回退到默认策略会撞上它）");
        assertTrue(out.contains("本段只给【事实】, 不给结论"),
            "缺少边界声明 —— 否则\"socket 可连\"会被误读成\"运行时健康\"");
    }

    @Test
    void missingXdgIsExplicitlyAnnotatedRatherThanSilent() {
        String out = ContainerRuntimeSocketProbe.renderFrom(null, null);
        assertTrue(out.contains("XDG_RUNTIME_DIR 未设置"),
            "未设置时必须明确标注, 不能沉默给空段:\n" + out);
        assertTrue(out.contains("rootless 策略会直接失败"), "未说明后果:\n" + out);
    }

    @Test
    void blankXdgIsTreatedAsUnset() {
        // "未设置"与"设为空串"必须给同一标注 —— 否则两种环境会得到两套不同语义
        String blank = ContainerRuntimeSocketProbe.renderFrom(null, "   ");
        assertTrue(blank.contains("XDG_RUNTIME_DIR 未设置"),
            "空白串必须等同于未设置, 实际:\n" + blank);
    }

    @Test
    void renderNeverThrows() {
        // 诊断自身失败绝不能影响测试结果 —— 覆盖各种退化输入
        String[][] inputs = {
            {null, null},
            {"", ""},
            {"unix://", ""},
            {"unix:///nonexistent-probe-seltest/x.sock", "/nonexistent-probe-seltest"},
            {"not-a-unix-url", "/run/user/1000"},
            {"unix:///run/podman/podman.sock", "/run/user/1000"},
        };
        for (String[] in : inputs) {
            String out = ContainerRuntimeSocketProbe.renderFrom(in[0], in[1]);
            assertFalse(out.isBlank(),
                "输入 DOCKER_HOST=" + in[0] + " XDG=" + in[1] + " 下输出为空");
        }
    }

    @Test
    void missingXdgDoesNotSuppressDockerHostReport() {
        // XDG 缺失时 DOCKER_HOST 那一段仍必须打印 —— 它正是"回退会撞上什么"的唯一线索
        String out = ContainerRuntimeSocketProbe.renderFrom("unix:///run/podman/podman.sock", null);
        assertTrue(out.contains("/run/podman/podman.sock"),
            "XDG 缺失时仍必须报告 DOCKER_HOST 的 socket:\n" + out);
        assertTrue(out.contains("XDG_RUNTIME_DIR 未设置"), "XDG 缺失必须标注:\n" + out);
    }

    @Test
    void nonUnixDockerHostIsNotTreatedAsSocket() {
        // DOCKER_HOST 可能是 tcp:// —— 若把整串当路径去 connect, 会得到一个
        // 与"socket 不可用"无关的结论, 从而误导（第五课: 探针查错了对象）
        String out = ContainerRuntimeSocketProbe.renderFrom("tcp://127.0.0.1:2375", "/run/user/1000");
        assertFalse(out.contains("tcp://127.0.0.1:2375 的 socket"),
            "非 unix:// 的 DOCKER_HOST 不应被当作 socket 路径去 connect");
        assertTrue(out.contains("/run/user/1000/podman/podman.sock"),
            "即便 DOCKER_HOST 非 unix, 仍必须报告策略实际使用的 socket:\n" + out);
    }
}
