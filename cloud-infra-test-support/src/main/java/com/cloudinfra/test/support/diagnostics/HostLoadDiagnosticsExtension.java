package com.cloudinfra.test.support.diagnostics;

import java.util.Locale;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * 集成测试失败时自动打印宿主现场快照。
 *
 * <p><b>为什么需要它</b>: 全量 IT 的失败信息形如
 * {@code Timed out waiting for log output matching '.*Nacos started successfully.*'}。
 * 这句话只回答"超时了"，<b>不回答"是慢了还是坏了"</b> —— 而这两者的处置完全相反:
 * <ul>
 *   <li><b>慢</b>(宿主 load 高, 每次 {@code podman exec} 实测 9~17 秒)
 *       ⇒ 加长 {@code STARTUP_TIMEOUT} 或降低并发即可</li>
 *   <li><b>起不来</b>(镜像/配置错) ⇒ 加长预算只会让每次失败都等更久</li>
 * </ul>
 * 二者<b>表象相同</b>，读错误文本无法区分（第八课：语义型失败不区分"参数没进来"与
 * "进来了但不对"）。历史上我为此重跑了多轮全量 IT，每次都要重新采集宿主状态 ——
 * 而<b>失败当时的现场已经没了</b>。
 *
 * <p><b>本扩展的价值不在修复，在留证</b>: 让下一次归因不必从头猜。
 * 判据必须落在失败现场，事后补采样的关联性远弱于同步记录。
 *
 * <p><b>不是修复的理由</b>: 它不会让任何失败消失。若某天它"修好了"问题，
 * 说明真正的根因是别的东西，本扩展只是恰好掩盖了它。
 *
 * <h2>⚠️ 注册方式: 只用 META-INF/services</h2>
 * 实测（2026-10-09）: 最初只放 {@code junit-platform.properties} 打开
 * auto-detection 开关, 在 test-support 自身的守护测试里【完全生效】,
 * 但在真实下游模块（notification-service）里探针故意失败时<b>诊断块不出现</b>。
 * 逐层排查: jar 内两份资源都在 ✅, 下游 test classpath 含该 jar ✅,
 * 全仓无第二份 properties 抢 key ✅ —— 最后才定位到<b>没有注册</b>:
 * auto-detection 依赖 ServiceLoader, 需要 {@code META-INF/services} 声明。
 * ⇒ 这是【机制存在 ≠ 链路接通】的又一例: 守护测试只覆盖了"自己所在模块"。
 * <p>⚠️ JUnit 6 <b>没有 {@code @AutoDetect} 注解</b>(2026-10-09 解包
 * junit-jupiter-api-6.0.3 实测: extension 包下不存在该类)。我先前按记忆写了
 * {@code @AutoDetect}, 结果 {@code cannot find symbol} —— 第五课补记六同形:
 * <b>按记忆猜 API 名字必错, 必须解包看真实内容</b>。
 * <b>但仍需在真实下游模块验证</b> —— 守护测试证明不了"别的模块能拿到它"。
 *
 * <h2>⚠️ 三条硬约束（都是本项目踩过的坑）</h2>
 * <ol>
 *   <li><b>绝不改变测试结果</b>：只读文件 + 只写 stderr。任何异常都吞掉，
 *       绝不让诊断失败把一个 PASS 变成 ERROR（第五课：探针失效不得改写结论）。</li>
 *   <li><b>只在容器相关失败时打印</b>：否则每个断言失败都刷一堆噪声，
 *       久而久之被无视 —— 噪声掩盖信号（第九课：守护覆盖面失控的同型）。</li>
 *   <li><b>不读 {@code OperatingSystemMXBean} 取负载</b>：它是平滑值，
 *       看不到瞬时尖峰；而本项目多个故障形态恰恰是<b>瞬时</b>负载高峰
 *       （第八课补记三）。直接读 {@code /proc/loadavg}。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么用 {@code /proc} 而非 cgroup</h2>
 * 2026-10-09 实测教训: {@code memory.current} 含 page cache，比提交量大得多
 * （某scope 报 8.47GB，实际 anon 仅 0.64GB）。而 {@code Committed_AS} 虽准确，
 * <b>超限并不等于分配会失败</b>（同轮实测 mmap 256MB 成功）。
 * ⇒ 三个指标各说一件事，<b>都不能单独作结论</b>，故本扩展同时打印并明确其边界。
 */
public class HostLoadDiagnosticsExtension implements TestWatcher, BeforeAllCallback, AfterAllCallback {

    /**
     * 已打印过快照的上下文体 —— 用于<b>去重</b>。
     *
     * <p>类级容器失败时 JUnit 会同时报一次 {@code class-level error} 与逐个方法失败,
     * 若不去重, 同一次失败会打印 N+1 份几乎相同的快照, 把真正的日志淹掉。
     */
    private static final java.util.Set<String> PRINTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 走过 {@code beforeAll} 的类 —— 用于类级失败兜底判断。 */
    private static final java.util.Set<String> CLASS_MARKED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 判定"与容器相关"的关键词 —— 覆盖 Testcontainers 与 Docker/Podman API 的报错文本。 */
    private static final List<String> CONTAINER_MARKERS = List.of(
        "ContainerLaunchException",
        "Could not create/start container",
        "Timed out waiting for log output",
        "waiting for log output matching",
        "ContainerStateException",
        "DockerException",
        "testcontainers",
        "CreateContainerError",
        "no such container");


    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        String root = rootCauseChain(cause);
        if (!looksLikeContainerFailure(root)) {
            return; // 约束 2: 与容器无关的失败不打印，避免噪声
        }
        emit(context, root);
    }

    /**
     * <b>类级容器失败的兜底</b>（2026-10-09 真实 Ryuk 失败轮发现）。
     *
     * <p>该轮 surefire XML 为 {@code <testcase name="" ...>} + 1 个 {@code <error>}:
     * 失败发生在<b>类级 {@code @Container} 启动</b>阶段, 即任何测试方法运行<b>之前</b>,
     * 而 {@link TestWatcher#testFailed} 只在<b>方法级</b>上下文触发 ⇒ 不被调用,
     * 现场快照零输出。
     *
     * <p>⇒ 这与"jar 是旧的"是两种不同的失效：<b>注册通了但钩子选错了</b>。
     * 守护测试当时全部通过, 因为它们只验"扩展能否被发现",
     * 没有验"类级容器失败时它是否被调用" —— 第三条硬规则(机制存在 ≠ 链路接通)。
     *
     * <p>这里<b>无法</b>拿到失败异常（{@code beforeAll} 抛异常时它走 Engine 异常链）,
     * 故改为：{@code beforeAll} 正常返回后打一个标记, 由 {@link #testFailed} 补打。
     * 若整个类因容器失败而一个方法都没跑到, {@code testFailed} 同样不会触发 ——
     * 因此额外在 {@code afterAll} 里检查标记, 若"进过 beforeAll 但从未 testFailed"则补打。
     */
    @Override
    public void beforeAll(ExtensionContext context) {
        // 标记仅用于类级兜底; 方法级失败仍由 testFailed 直接处理
        CLASS_MARKED.add(context.getTestClass().map(Class::getName).orElse("?"));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        String cls = context.getTestClass().map(Class::getName).orElse("?");
        if (CLASS_MARKED.remove(cls) && !PRINTED.contains(cls)) {
            // 进过 beforeAll 却一次 testFailed 都没有 ⇒ 典型的类级容器失败
            System.err.println(renderSnapshot(cls + "#<class-level>",
                "(未捕获到异常对象) 类级容器在测试方法执行前失败, beforeAll 未抛异常但无任何方法结果"));
        }
    }

    private static void emit(ExtensionContext context, String root) {
        String key = describe(context);
        if (!PRINTED.add(key)) {
            return; // 同一次失败已打印过
        }
        try {
            System.err.println(renderSnapshot(key, root));
        } catch (Throwable t) {
            // 约束 1: 诊断自身失败绝不影响测试结果
            System.err.println("[宿主诊断] 诊断自身失败(不影响测试结果): " + t);
        }
    }

    private static String describe(ExtensionContext context) {
        return context.getTestClass().map(Class::getName).orElse("?")
            + '#' + context.getTestMethod().map(m -> m.getName()).orElse("?");
    }

    /** 沿 {@code getCause()} 链拼出全部消息 —— 因为真正的线索常在最深一层(第八课: 逐层读 Caused by)。 */
    private static String rootCauseChain(Throwable cause) {
        StringBuilder sb = new StringBuilder();
        Throwable t = cause;
        int depth = 0;
        while (t != null && depth < 12) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            t = t.getCause();
            depth++;
        }
        return sb.toString();
    }

    static boolean looksLikeContainerFailure(String chain) {
        String lower = chain.toLowerCase(Locale.ROOT);
        for (String marker : CONTAINER_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String renderSnapshot(String testId, String chain) {
        StringBuilder out = new StringBuilder();
        out.append("\n").append("=".repeat(72)).append('\n');
        out.append("==== [容器启动失败 · 宿主现场快照] ====\n");
        out.append("失败测试 : ").append(testId).append('\n');
        out.append("失败时刻 : ").append(LocalDateTime.now()).append('\n');
        out.append("异常链   : ").append(chain.replaceAll("\\s+", " ")).append('\n');

        out.append("---- 宿主账本 ----\n");
        out.append("load(1/5/15min) : ").append(readLoadavg()).append('\n');
        out.append("CPU 核数        : ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        long committed = readMeminfoKb("Committed_AS");
        long limit = readMeminfoKb("CommitLimit");
        long available = readMeminfoKb("MemAvailable");
        long total = readMeminfoKb("MemTotal");
        out.append("Committed_AS    : ").append(gb(committed)).append("  (提交额度, **超限≠分配失败**, 需实测 mmap)\n");
        out.append("CommitLimit     : ").append(gb(limit))
           .append(limit > 0 ? String.format("   占比 %.1f%%", committed * 100.0 / limit) : "").append('\n');
        out.append("MemAvailable    : ").append(gb(available)).append('\n');
        out.append("MemTotal        : ").append(gb(total)).append('\n');

        out.append("---- 判读 ----\n");
        // ⚠️ 判读必须【给判据, 不给结论】。写死"load 高 ⇒ 启动慢"会把一个尚未
        //    受控证明的假设(第八课补记三: 高 load 与失败相关但未定性)固化进输出,
        //    下一个人会拿它当已证事实 —— 这正是第五课的"看起来正常的错误信息"。
        //    正确做法: 打印比值让读者自己判断, 并列出两种机制的不同处置。
        out.append("· load1/核数 = ")
           .append(String.format("%.2f", loadRatio()))
           .append("   (比值高 ⇒ 启动慢; 比值低 ⇒ 更像起不来。尚需受控对照定性)\n");
        out.append("· 两种机制表象相同但处置相反:\n");
        out.append("  - 【慢】(load 高): 加长 InfrastructureContainers.STARTUP_TIMEOUT 或降低并发\n");
        out.append("  - 【起不来】(load 低): 查镜像/配置 —— 加长预算只会让每次失败等更久\n");
        out.append("· `no such container` 是第三种形态(容器被删), 与上面两者都不同类\n");
        out.append("· MemAvailable 充足但仍失败 ⇒ 不是物理内存不足(账面 ≠ 可分配)\n");
        out.append("=".repeat(72)).append('\n');
        // ES 分片取证 —— 补上先前七轮排查一直缺的失败当刻 ES 侧读数。
        // 自动发现容器(exec 内 curl), 不需要外部传入端点。
        // 失败/未找到容器时它自己会标注"未取证", 不会拖垮本快照。
        out.append(ElasticsearchContainerProbe.readShardState());
        // socket 连通性 —— `Broken pipe` 类失败的【唯一】可用判据。
        // 2026-10-10 实测: 该故障已闭合到"Podman socket 写入侧", 但快照原先不含
        // "Testcontainers 连的是哪个 socket / 此刻能否连上", 导致每次都要重新考古,
        // 而本机两个候选 socket 一个可连一个 Permission denied —— 极易再次采错对象。
        out.append(ContainerRuntimeSocketProbe.render());
        return out.toString();
    }

    private static String gb(long kb) {
        // ⚠️ 探针失效必须显式标注, 不能显示成 "-0.00 GB"(第五课补记五:
        //    参数/配置"送到了但没生效"会伪装成合法值)。-1 是 readMeminfoKb 的失效哨兵。
        return kb < 0 ? "(读取失败)" : String.format("%.2f GB", kb / 1024.0 / 1024.0);
    }

    /** load(1min) 与 CPU 核数的比值 —— 只给判据, 由读者结合其他信号判断。 */
    private static double loadRatio() {
        String raw = readLoadavg();
        try {
            return Double.parseDouble(raw.trim().split("\\s+")[0])
                / Math.max(1, Runtime.getRuntime().availableProcessors());
        } catch (RuntimeException e) {
            return -1.0;
        }
    }

    static String readLoadavg() {
        try {
            return Files.readString(Path.of("/proc/loadavg")).trim();
        } catch (IOException | RuntimeException e) {
            return "(读取失败: " + e.getClass().getSimpleName() + ")";
        }
    }

    static long readMeminfoKb(String key) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of("/proc/meminfo"));
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
        List<String> hits = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith(key + ":")) {
                hits.add(line.replaceAll("[^0-9]", ""));
            }
        }
        if (hits.isEmpty()) {
            return -1L;
        }
        return Long.parseLong(hits.get(0));
    }
}
