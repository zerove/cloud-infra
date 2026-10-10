package com.cloudinfrait.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    /** 去掉行注释与块注释, 只留代码, 避免注释里的字被判据当成证据。 */
    private static String stripComments(String src) {
        return src
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    @Test
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
