package com.cloudinfrait.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 守护"修复路径是否真被 IT 覆盖", 而不只是"修复代码是否存在"。
 *
 * <p>起因是 2026-10-10 的一次自查: {@code SearchServiceIntegrationTest} 里唯一一处
 * {@code rebuildIndex} 调用传的是 {@code "unknown-index"}, 而实现第一件事就是
 * {@code KNOWN_INDEXES.get(name)} 为 null 即抛 {@code IllegalArgumentException}。
 * ⇒ 删除-重建路径(含新加的分片就绪等待)在 IT 中<b>零覆盖</b>。
 *
 * <p>⚠️ 为什么这件事必须显式化: 该 IT 跑出 7/7 全绿, 但<b>无论修复在与不在都会绿</b>。
 * 按本项目"守护测试的有效性只有负向对照能证明"的要求, 那种绿不能算证据。
 */
class RebuildPathCoverageGuardTest {

    private static final Path SERVICE_IMPL =
            Path.of("src/main/java/com/cloudinfra/search/service/impl/IndexAdminServiceImpl.java");
    private static final Path IT_FILE =
            Path.of("src/test/java/com/cloudinfra/search/SearchServiceIntegrationTest.java");

    @Test
    @DisplayName("非法索引名只走参数校验分支, 不能当作删除-重建路径的覆盖证据")
    void unsupportedIndexNameCannotProveDeleteCreateCoverage() throws Exception {
        String impl = Files.readString(SERVICE_IMPL);
        String it = Files.readString(IT_FILE);

        // 前提 1: 参数校验确实排在 delete-then-create 之前(否则"非法名"也能删索引)
        int guard = impl.indexOf("不支持的索引");
        int delete = impl.indexOf("indexOps.delete();");
        assertThat(guard).as("实现必须仍含参数校验文案, 否则本守护已失去被守护对象").isGreaterThanOrEqualTo(0);
        assertThat(delete).as("实现必须仍含 indexOps.delete(), 否则本守护已失去被守护对象").isGreaterThanOrEqualTo(0);
        assertThat(guard).as("参数校验必须排在 delete() 之前, 否则非法索引名也能触发删除-重建").isLessThan(delete);

        // 前提 2: IT 仍以字面量调用 rebuildIndex(否则本守护已无被守护对象)
        assertThat(it.contains("rebuildIndex(\""))
                .as("IT 必须仍以字面量调用 rebuildIndex, 否则本守护已失去被守护对象")
                .isTrue();

        // 判定: 传入实现已知索引名的调用才算覆盖删除-重建路径。
        // 索引名从实现的 KNOWN_INDEXES 里提取 —— 手写会与实现漂移而恒假绿(第五课)。
        List<String> known = extractKnownIndexNames(impl);
        assertThat(known)
                .as("必须能从实现里提取到 KNOWN_INDEXES 的键名; 提取失败=扫描器坏了, 不等于通过")
                .isNotEmpty();

        boolean coversDeleteCreate = known.stream()
                .anyMatch(name -> it.contains("rebuildIndex(\"" + name + "\""));

        // 断言方向: 必须【已覆盖】。
        // 2026-10-10 补用例前此处断言 false(记录"当前未覆盖"), 属临时姿态;
        // 已新增 testRebuildIndexExecutesDeleteThenCreateWithReadinessWait 走真实路径,
        // 故改为 true —— 即从"记录现状"升级为"守护该性质"。
        //
        // ⚠️ 该断言的反面价值同样重要: 若有人删掉那个用例, 本守护会立刻报红,
        // 从而堵住"删掉唯一覆盖 → 绿灯依旧 → 修复再次失去证据"这条退路。
        assertThat(coversDeleteCreate)
                .as("删除-重建路径必须被 IT 覆盖: 需存在一个传入 KNOWN_INDEXES 真实键名"
                        + "(如 " + known.get(0) + ")的 rebuildIndex 调用, 否则 delete/等待/create 零执行。")
                .isTrue();
    }

    /** 从 {@code KNOWN_INDEXES} 声明里提取字符串键名(纯文本解析, 不触发类加载)。 */
    private static List<String> extractKnownIndexNames(String impl) {
        int start = impl.indexOf("KNOWN_INDEXES");
        if (start < 0) {
            return List.of();
        }
        int end = impl.indexOf(";", start);
        if (end < 0) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        Matcher m = Pattern.compile("\"([a-z0-9_\\-]+)\"").matcher(impl.substring(start, end));
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }
}
