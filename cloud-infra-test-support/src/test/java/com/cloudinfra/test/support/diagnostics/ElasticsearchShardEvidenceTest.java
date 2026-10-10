package com.cloudinfra.test.support.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 守护 ES 分片取证的探针纪律。
 *
 * <p>核心不变量: <b>"没取到"绝不能长得像"取到了 0"</b>。
 * 这是第八课补记一(探针失效时会把被测系统判成失败)与
 * 第五课(看起来正常的错误信息)的交叉点。
 */
class ElasticsearchShardEvidenceTest {

    private static final Duration REAL_TIMEOUT = ElasticsearchShardEvidence.timeout;

    @org.junit.jupiter.api.BeforeEach
    void shortenTimeout() {
        // ⚠️ 单测不应等真实网络超时: 未覆写时实测本类耗时 60.67s
        ElasticsearchShardEvidence.timeout = Duration.ofMillis(200);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreTimeout() {
        // ⚠️ 必须还原而不是置空: 同 JVM 内其他测试会读它(第五课补记: 共享可变状态)
        ElasticsearchShardEvidence.timeout = REAL_TIMEOUT;
    }

    @Test
    @DisplayName("未提供端点时必须显式标注未取证, 不得返回空串")
    void nullEndpointIsExplicitlyMarked() {
        assertThat(ElasticsearchShardEvidence.collect(null))
                .as("null 端点必须标注, 不能返回空串 —— 空串会被读成'一切正常'")
                .contains("未取证")
                .isNotBlank();
    }

    @Test
    @DisplayName("空白端点与空串端点同样标注未取证")
    void blankEndpointIsMarked() {
        assertThat(ElasticsearchShardEvidence.collect("   ")).contains("未取证");
    }

    @Test
    @DisplayName("端点不可达时数据行标注传输失败, 不得显示成 0 或空")
    void unreachableEndpointReportsTransportFailure() {
        String out = ElasticsearchShardEvidence.collect("http://127.0.0.1:1");

        // ⚠️ 判据必须只扫【数据行】(以 "· " 开头的三行), 不能扫全文。
        // 首版用 contains("未取证") 扫全文, 而判读段第 83 行固定写着
        // "· 注意: (未取证) 说明探针本身失败" => 数据行即使被清空, 断言仍成立,
        // 即【恒假绿】。这与 AGENTS.md 补记三十二(方法名成为判据字面量)同型:
        // 任何"扫全文找关键词"的断言都会被【说明文档本身】满足。
        String dataLines = out.lines()
                .filter(l -> l.startsWith("· "))
                .filter(l -> l.contains("端点") || l.contains("_cluster") || l.contains("_cat") || l.contains("pending"))
                .reduce("", (a, b) -> a + b + "\n");

        assertThat(dataLines)
                .as("数据行必须存在, 不能为空")
                .isNotBlank();
        assertThat(dataLines)
                .as("不可达时【数据行】必须标注未取证, 而不是空串或 0")
                .contains("未取证");
        assertThat(dataLines)
                .as("探针失败时不得出现任何看起来像健康数据的行")
                .doesNotContain("green").doesNotContain("active_primary_shards\": ");
    }

    @Test
    @DisplayName("输出必须含判读段, 且不断言根因")
    void outputIncludesInterpretationWithoutClaimingRootCause() {
        String out = ElasticsearchShardEvidence.collect(null);

        assertThat(out).contains("判读");
        assertThat(out)
                .as("判读必须写成'前提/需对照实验', 不得把假设写成结论")
                .contains("需对照实验");
        assertThat(out)
                .as("必须提示未取证不等于正常")
                .contains("不等于 ES 正常");
    }

    @Test
    @DisplayName("尾斜杠不应产生双斜杠 URL")
    void trailingSlashIsNormalized() {
        // 通过不可达时的输出观察端点回显: 应为单斜杠
        String out = ElasticsearchShardEvidence.collect("http://127.0.0.1:1/");

        assertThat(out)
                .as("端点回显应已去掉尾斜杠")
                .contains("http://127.0.0.1:1");
        assertThat(out)
                .as("不得出现双斜杠路径")
                .doesNotContain("127.0.0.1:1//");
    }
}
