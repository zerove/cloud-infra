package com.cloudinfrait.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 守护 rebuild 与 deleteAll 的就绪等待。
 *
 * <p>背景: search-service 的 IT 在全量运行时报
 * {@code no_shard_available_action_exception} (ES 503)。
 * 错误机制已由 ES 自身的 JSON 错误体确认, 但触发条件未闭合。
 * 本守护针对其中【已证实的代码事实】, 不假设根因:
 *
 * <ol>
 *   <li>{@code @BeforeEach} 的 {@code operLogRepository.deleteAll()} 每个用例都触发,
 *       内部发 {@code _delete_by_query}, 其 search 阶段需要可用分片。
 *   <li>{@code rebuildIndex} 是 delete-then-create 且中间无任何就绪等待。
 * </ol>
 *
 * <p>注意: 本守护【不】声称这两个调用是 503 的根因 ——
 * 五变量受控对照显示单容器下二者均可成功(200)。
 * 它守护的是"不得引入无等待的删除-重建序列"这一性质,
 * 避免在根因闭合前扩大暴露面。
 *
 * <p>见 AGENTS.md 第九课补记三十/三十一。
 */
class RebuildReadinessGuardTest {

    private static final Path SERVICE_IMPL =
            Path.of("src/main/java/com/cloudinfra/search/service/impl/IndexAdminServiceImpl.java");
    private static final Path IT_FILE =
            Path.of("src/test/java/com/cloudinfra/search/SearchServiceIntegrationTest.java");

    @Test
    @DisplayName("rebuildIndex 在 delete 与 create 之间必须等待分片就绪")
    void rebuildWaitsForShardReadinessBetweenDeleteAndCreate() throws Exception {
        String src = Files.readString(SERVICE_IMPL);

        int delete = src.indexOf("indexOps.delete();");
        int create = src.indexOf("indexOps.createWithMapping();");

        assertThat(delete)
                .as("必须仍存在 indexOps.delete(), 否则本守护已失去被守护对象")
                .isGreaterThanOrEqualTo(0);
        assertThat(create)
                .as("必须仍存在 indexOps.createWithMapping(), 否则本守护已失去被守护对象")
                .isGreaterThanOrEqualTo(0);
        // rebuildIndex 是 delete-then-create: 命中的是【第一个】delete(104 行),
        // 第二个 delete(117 行) 属于 deleteIndex, 不能用 lastIndexOf。
        assertThat(create)
                .as("createWithMapping() 必须落在 rebuildIndex 的 delete() 之后")
                .isGreaterThan(delete);

        String between = src.substring(delete, create);

        // ⚠️ 判据只认【真实调用语句】, 不扫关键字子串。
        // 首版用 containsAnyOf("health","await",...) 判绿, 但方法名
        // awaitShardsReleased 本身就含 "await" => 删掉调用、留着注释也能过,
        // 即【恒假绿】。这与第五课补记一「扫描器自己坏掉时长成守护的样子」同型。
        // 负向对照已验证: 注释掉调用后首版判据仍判绿。
        String codeOnly = stripComments(between);

        assertThat(codeOnly)
                .as("delete() 与 createWithMapping() 之间的【代码】(已去注释) 必须含就绪等待调用")
                .containsPattern("(?s).*\\b(await\\w*\\s*\\(|\\.health\\s*\\(|waitFor\\w*\\s*\\(|refresh\\w*\\s*\\().*");
    }

    /**
     * 就绪等待的【判据语义】必须与目标索引绑定（2026-10-10 新增）。
     *
     * <p>⚠️ 本项针对一个已实测的假门禁: 上一项断言的正则是
     * {@code (await\w*\s*\(|\.health\s*\(|waitFor\w*\s*\(|refresh\w*\s*\()},
     * 它只要匹配到 <b>调用名</b> 就通过, <b>不检查调用后面判断的是什么</b>。
     *
     * <p>负向对照（已实测, 守护当时判绿）:
     * <pre>
     *   // 原判据
     *   if (health.getRelocatingShards() == 0 && health.getInitializingShards() == 0) return;
     *   // 注入: 换成恒真且与目标索引无关的条件
     *   if (health.getNumberOfDataNodes() >= 0) return;
     *   → RebuildReadinessGuardTest 仍然 BUILD SUCCESS
     * </pre>
     *
     * <p>根因: {@code ClusterHealth} 只暴露<b>集群级</b>计数, 没有任何按索引查询的方法
     * （javap 确认: 无 {@code getIndex(...)} / per-index 字段）。因此现有实现
     * <b>在原理上就无法确认目标索引已释放</b> —— 它看到的是全集群的分片活动。
     *
     * <p>本项守护"判据必须引用目标索引"这一性质。它允许三种正确形态:
     * <ol>
     *   <li>直接引用索引标识（如 {@code indexName} / {@code getIndexName()}）;</li>
     *   <li>查询索引级 API（如 {@code indexOps}、{@code getShardState}、
     *       {@code shardRoutings}、{@code refresh} 等按索引生效的方法）;</li>
     *   <li>显式调用 ES 的索引级端点（形如 {@code _cat/indices} 的通配查询）。</li>
     * </ol>
     *
     * <p>若将来改成"查询目标索引是否存在"而非"分片是否释放", 只要在代码里出现索引标识即可。
     * 本项<b>不</b>断言具体策略 —— 那是根因闭合后的选择, 不是守护该管的事。
     */
    @Test
    @DisplayName("就绪等待的判据必须引用目标索引, 不能只看集群级计数")
    void readinessCriterionIsBoundToTargetIndex() throws Exception {
        String src = Files.readString(SERVICE_IMPL);

        int delete = src.indexOf("indexOps.delete();");
        int create = src.indexOf("indexOps.createWithMapping();");
        assertThat(create).as("前提: createWithMapping() 必须存在").isGreaterThan(delete);

        String waitMethod = extractWaitMethodBody(src);
        assertThat(waitMethod)
                .as("必须能定位到 await* 等待方法体 —— 若方法被改名/删除, 本守护失去被守护对象, "
                        + "请同步更新本测试而不是删除它")
                .isNotNull();

        // 集群级计数: 这些字段与"目标索引是否已释放"没有因果绑定
        assertThat(waitMethod)
                .as("ClusterHealth 只有集群级计数(无按索引查询), 单独使用它无法确认目标索引已释放; "
                        + "判据必须同时引用索引标识或索引级 API")
                .doesNotContainPattern("(?s).*getRelocatingShards\\(\\)\\s*==\\s*0"
                        + ".*getInitializingShards\\(\\)\\s*==\\s*0.*")
                .doesNotContainPattern("(?s).*getActiveShardsPercent\\(\\).*");

        // 反过来: 判据必须【确实】引用了目标索引或索引级 API
        boolean boundToIndex = waitMethod.matches("(?s).*(indexName|getIndexName\\(\\)|indexOps"
                + "|getShardState|shardRoutings|IndexCoordinates).*");
        assertThat(boundToIndex)
                .as("等待判据必须引用目标索引(索引名/索引级 API), 而非只看集群级计数:\n" + waitMethod)
                .isTrue();
    }

    /** 取出 await* 等待方法的方法体源码; 找不到返回 null(由调用方断言)。 */
    private static String extractWaitMethodBody(String src) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("private\\s+void\\s+(await\\w*)\\s*\\([^)]*\\)\\s*\\{")
                .matcher(src);
        if (!m.find()) {
            return null;
        }
        int start = m.end() - 1;   // 指向 '{'
        int depth = 0;
        for (int i = start; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(start, i + 1);
                }
            }
        }
        return src.substring(start);
    }

    /** 去掉行注释与块注释, 只留代码, 避免注释里的字被判据当成证据。 */
    private static String stripComments(String src) {
        return src
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 已知待办: {@code @BeforeEach} 的 deleteAll 缺就绪等待。
     *
     * <p>⚠️ 本项标记为 {@code @Disabled} 而非任其失败: 它是 {@code *Test.java},
     * 会被 surefire 默认拾取 ⇒ 任其失败就是【让默认构建变红】。
     * 那是本项目自己反复批评的“为了绿而绿”的反面 —— 但更糟:
     * {@code deleteAll} 缺等待是否真是503 的成因**尚未闭合**,
     * 加等待属于**没有依据的修复**; 而让构建红又会阻塞所有人。
     *
     * <p>正确处置是显式待办而非假装通过, 故用 {@code @Disabled} + 原因。
     *
     * <p><b>解除条件</b>: 补记三十一的五变量受控对照显示该路径返回 200,
     * 即【单容器下无法复现】。只有拿到“宿主资源竞争下该路径确实失败”的证据,
     * 才可为它加等待; 若最终证明无需等待, 则删除本测试并在此记录结论。
     *
     * <p><b>2026-10-11 复核(同向, 仍不解除)</b>: {@code IndexAdminServiceImpl} 的
     * {@code awaitShardsReleased} 已改为索引级判据。为判断"索引级判据是否真的消除了 503",
     * 把该判据退回<b>集群级形态(原始缺陷)</b>后重跑 {@code SearchServiceIntegrationTest},
     * 结果仍是 <b>8/8 全绿、0 次 no_shard_available</b>。
     * ⇒ 原 503 在单容器条件下同样不可复现, 本项的解除条件<b>未被满足</b>, 保持 {@code @Disabled}。
     * 这也解释了为何 {@code IndexAdminServiceImpl} 侧只能声称"判据已绑定索引"
     * 而不能声称"503 已修复"。
     */
    @Test
    @Disabled("缺就绪等待是否致 503 尚未闭合; 加等待属无依据修复, 任其失败会弄红默认构建")
    @DisplayName("@BeforeEach 的 deleteAll 不得在分片未就绪时裸调用")
    void beforeEachDeleteAllIsNotBare() throws Exception {
        String src = Files.readString(IT_FILE);

        List<String> lines = List.of(src.split("\\R"));
        int idx = indexOfLineContaining(lines, "operLogRepository.deleteAll()");

        assertThat(idx)
                .as("必须仍存在 operLogRepository.deleteAll(), 否则本守护已失去被守护对象")
                .isGreaterThanOrEqualTo(0);

        // 该行必须处于 @BeforeEach 内, 且方法内在此之前有分片就绪相关调用或注释说明
        String window = String.join("\n", lines.subList(
                Math.max(0, idx - 12), Math.min(lines.size(), idx + 2)));

        boolean guarded = window.contains("health")
                || window.contains("await")
                || window.contains("Awaitility")
                || window.contains("refresh");

        assertThat(guarded)
                .as("@BeforeEach 的 deleteAll() 之前需有分片就绪等待, 或至少显式注释说明为何无需等待。"
                        + " 若确认无需等待, 请在该处加注释并同步放宽本断言 —— "
                        + "但不要直接删除本测试。")
                .isTrue();
    }

    private static int indexOfLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
