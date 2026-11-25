package com.cloudinfra.test.support.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 容器发现 —— 通过 {@code podman ps} 找到 ES 容器并在其内部取分片读数。
 *
 * <p>为什么必须走 exec: rootless + pasta 网络下宿主无法直连容器 IP
 * ({@code inspect} 的 {@code NetworkSettings} 全字段为空), 且容器端口默认不映射。
 * 详见 AGENTS.md 第九课补记三十。
 *
 * <p>⚠️ 三条硬要求(第五课 / 第八课补记):
 * <ul>
 *   <li><b>必须 {@code --noproxy '*'}</b>: {@code podman exec} 会继承宿主代理环境变量,
 *       否则 curl 去连代理端口而失败。{@code no_proxy} 通常只含 {@code localhost},
 *       不含 {@code 127.0.0.1}。</li>
 *   <li><b>命令不存在</b>与<b>命令失败</b>必须区分, 前者是环境问题。</li>
 *   <li>返回值区分"取到值"与"取不到值", 不允许把后者渲染成前者。</li>
 * </ul>
 */
final class ElasticsearchContainerProbe {

    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(20);

    private ElasticsearchContainerProbe() {
    }

    /**
     * 在首个名字含 {@code elasticsearch} 的运行中容器内取分片读数。
     *
     * @return 多行读数; 未找到容器或取不到值时返回明确标注, 绝不返回空串
     */
    static String readShardState() {
        String ps = run(List.of("podman", "ps", "--format", "{{.Names}}"));
        if (ps == null) {
            return "(未取证: podman 命令不可用或超时)";
        }
        if (ps.startsWith("(未取证")) {
            return ps;
        }

        String target = findEsContainer(ps);
        if (target == null) {
            return "(未取证: 未找到 ES 容器; 运行中容器名: " + summarize(ps) + ")";
        }

        String body = execIn(target,
                "curl -s -m 10 --noproxy '*' http://127.0.0.1:9200/_cluster/health");
        if (body == null) {
            return "(未取证: 容器 " + target + " 内 curl 无输出或失败)";
        }

        StringBuilder out = new StringBuilder();
        out.append("容器: ").append(target).append('\n');
        out.append("· _cluster/health: ").append(oneLine(body)).append('\n');

        String shards = execIn(target,
                "curl -s -m 10 --noproxy '*' 'http://127.0.0.1:9200/_cat/shards?v"
                        + "&h=index,shard,prirep,state,node,unassigned.reason'");
        out.append("· _cat/shards  : ").append(oneLine(shards)).append('\n');

        out.append("· 判读: 主分片不可用(active_primary_shards==0 且有索引)才会出现\n");
        out.append("  no_shard_available_action_exception; initializing_shards>0 只是相关,\n");
        out.append("  是否导致失败【需对照实验】。\n");
        out.append("=".repeat(72)).append('\n');
        return out.toString();
    }

    /**
     * 定位 ES 容器。
     *
     * <p>⚠️ 第一版只认名字含 {@code elasticsearch} 的容器, 在真实场景下失效 ——
     *   实测容器名为 {@code es-snap-check}(不含该词)且 ES 明确 green 时,
     *   探针仍报"未找到容器"。即【判据比真实情况窄】, 与第五课补记五同型。
     *
     * <p>改为两级: 先按名字(便宜), 再按【是否真的有 ES 在 9200 应答】(可靠)。
     * 后者是判别式 —— 名字只是启发式, 端口应答才是事实。
     */
    private static String findEsContainer(String ps) {
        List<String> names = ps.lines().map(String::trim).filter(n -> !n.isEmpty()).toList();

        for (String n : names) {
            if (n.toLowerCase(java.util.Locale.ROOT).contains("elasticsearch")) {
                return n;
            }
        }
        // 名字没命中就逐个探测 —— 容器少时开销可接受, 且只在失败快照里调用
        for (String n : names) {
            String body = execIn(n, "curl -s -m 5 --noproxy '*' http://127.0.0.1:9200/");
            if (body != null && !body.startsWith("(未取证") && !"(超时)".equals(body)
                    && body.contains("cluster_name")) {
                return n;
            }
        }
        return null;
    }

    private static String summarize(String ps) {
        List<String> names = ps.lines().map(String::trim).filter(n -> !n.isEmpty()).toList();
        return names.isEmpty() ? "(无)" : String.join(", ", names);
    }

    private static String execIn(String container, String shellCmd) {
        ProcessBuilder pb = new ProcessBuilder(
                "podman", "exec", container, "sh", "-c", shellCmd);
        pb.redirectErrorStream(false);   // 保留 stderr, 不与 stdout 混成一团
        try {
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(EXEC_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "(超时)";
            }
            if (out.isBlank() && !err.isBlank()) {
                return "(未取证: " + err.lines().findFirst().orElse("stderr 非空但无内容") + ")";
            }
            return out;
        } catch (IOException e) {
            return "(未取证: " + e.getClass().getSimpleName() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "(未取证: 被中断)";
        }
    }

    private static String run(List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(EXEC_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return out;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String oneLine(String s) {
        if (s == null || s.isBlank()) {
            return "(空)";
        }
        String v = s.replaceAll("\\s+", " ").trim();
        return v.length() <= 400 ? v : v.substring(0, 400) + "...(截断)";
    }
}
