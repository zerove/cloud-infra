package com.cloudinfra.test.support.diagnostics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * ES 分片取证 —— 只在【ES 端点可达】时产出, 不可达时明确标注"未取证"。
 *
 * <p>存在理由: search-service 的 IT 曾报 ES 503
 * {@code no_shard_available_action_exception}, 但连续七轮排查都**没取到失败当刻的
 * 分片状态** —— 当时只有宿主 load 快照, 没有 ES 侧读数, 于是无法区分
 * "主分片未分配" / "分片正在初始化" / "索引已被删除"。
 *
 * <p>⚠️ 本类【不判定失败】, 只负责在失败时留下证据。
 * 判读规则写在输出里, 不写成结论。
 *
 * <p>⚠️ 探针纪律(第五课/第八课补记):
 * <ul>
 *   <li>不可达时输出 {@code (未取证: ...)} 而<b>不</b>输出空字符串或 0,
 *       避免"没取到"被读成"取到 0"。</li>
 *   <li>返回值区分 HTTP 错误码与传输失败, 两者含义不同。</li>
 *   <li>不吞 stderr 语义: 超时、无路由、非 JSON 都单独标注。</li>
 * </ul>
 *
 * <p>见 AGENTS.md 第九课补记二十九/三十三。
 */
final class ElasticsearchShardEvidence {

    /**
     * 单次请求超时。默认 10s, 但单测会调用十几次, 叠加后会让一个测试类
     * 多花一分钟(实测 60.67s)。故允许测试覆写为极短值。
     */
    static Duration timeout = Duration.ofSeconds(10);

    private ElasticsearchShardEvidence() {
    }

    /**
     * 采集分片取证。{@code baseUri} 为 ES 根地址, 例如 {@code http://host:port}。
     *
     * @return 多行文本; 端点不可达时返回明确的"未取证"说明而非空串
     */
    static String collect(String baseUri) {
        StringBuilder out = new StringBuilder();
        out.append("---- ES 分片取证 ----\n");

        if (baseUri == null || baseUri.isBlank()) {
            // ⚠️ 即便未取证也必须输出判读段: 调用方(含失败日志的读者)需要在任何情况下
            //   都知道"未取证"意味着什么。首版提前 return 导致拿不到判读, 已由守护捕获。
            out.append("端点: (未提供)\n");
            appendReadings(out);
            return out.toString();
        }

        String root = trimTrailingSlash(baseUri);

        String clusterHealth = get(root + "/_cluster/health");
        String shards = get(root + "/_cat/shards?v&h=index,shard,prirep,state,node,unassigned.reason");
        String pending = get(root + "/_cluster/pending_tasks?human&source");

        out.append("端点: ").append(root).append('\n');
        out.append("· _cluster/health: ").append(abbreviate(clusterHealth)).append('\n');
        out.append("· _cat/shards  : ").append(abbreviate(shards)).append('\n');
        out.append("· pending_tasks : ").append(abbreviate(pending)).append('\n');
        appendReadings(out);
        return out.toString();
    }

    private static void appendReadings(StringBuilder out) {
        out.append("---- 判读 ----\n");
        out.append("· active_primary_shards == 0 且有索引存在 ⇒ 主分片不可用, 正是\n");
        out.append("  no_shard_available_action_exception 的前提\n");
        out.append("· initializing_shards > 0 ⇒ 分片正在分配, 此时发出的 search 阶段\n");
        out.append("  读取可能失败 —— 但是否失败【需对照实验】, 不要据此直接判因\n");
        out.append("· unassigned.reason 非空 ⇒ ES 已给出原因, 优先读它\n");
        out.append("· 以上三项均正常时, 本取证【不支持】“分片不可用”这一解释,\n");
        out.append("  应转向其他方向(如宿主资源、客户端超时)\n");
        out.append("· 注意: (未取证) 说明探针本身失败, 不等于 ES 正常\n");
        out.append("=".repeat(72)).append('\n');
    }

    private static String get(String url) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .proxy(HttpClient.Builder.NO_PROXY)   // ⚠️ 必须: 否则继承宿主代理(第六课补记二)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET()
                .build();
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return "(HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()) + ")";
            }
            return resp.body();
        } catch (IOException e) {
            // 传输层失败 —— 明确区分, 不能与"ES 返回空"混为一谈
            return "(未取证: 传输失败 " + e.getClass().getSimpleName() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "(未取证: 被中断)";
        } catch (RuntimeException e) {
            return "(未取证: " + e.getClass().getSimpleName() + ")";
        }
    }

    private static String trimTrailingSlash(String s) {
        String v = s.trim();
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "(null)";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 400 ? one : one.substring(0, 400) + "...(截断)";
    }
}