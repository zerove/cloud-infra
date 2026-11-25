package com.cloudinfra.common.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageQuery} 缺省值与钳制语义 (2026-10-18, 报告 A1)。
 *
 * <p>钳制语义承接 notification 控制器此前的内联实现:
 * {@code Math.max(page, 1)} / {@code Math.min(Math.max(size, 1), 100)}。
 * 本测试把该行为固定下来, 作为统一分页请求形态的契约 —— 若有人放宽上限或
 * 让越界值透传, 这里必须变红。</p>
 */
class PageQueryTest {

    @Test
    @DisplayName("未设置时取默认值 (pageNum=1, pageSize=10)")
    void defaultsWhenUnset() {
        PageQuery query = new PageQuery();
        assertThat(query.normalizedPageNum()).isEqualTo(1);
        assertThat(query.normalizedPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("显式置 null 时同样回落到默认值")
    void nullFallsBackToDefaults() {
        PageQuery query = new PageQuery();
        query.setPageNum(null);
        query.setPageSize(null);
        assertThat(query.normalizedPageNum()).isEqualTo(1);
        assertThat(query.normalizedPageSize()).isEqualTo(10);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-5,1", "1,1", "2,2", "1000,1000"})
    @DisplayName("pageNum 钳制: 小于 1 钳到 1, 其余原样")
    void clampsPageNum(int input, int expected) {
        PageQuery query = new PageQuery();
        query.setPageNum(input);
        assertThat(query.normalizedPageNum())
                .as("pageNum=%d 应钳制为 %d", input, expected)
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-3,1", "1,1", "100,100", "101,100", "1000,100"})
    @DisplayName("pageSize 钳制: 固定区间 [1, 100]")
    void clampsPageSize(int input, int expected) {
        PageQuery query = new PageQuery();
        query.setPageSize(input);
        assertThat(query.normalizedPageSize())
                .as("pageSize=%d 应钳制为 %d", input, expected)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("钳制不改写原始值 (便于回显与排查)")
    void normalizationDoesNotMutateRawValues() {
        PageQuery query = new PageQuery();
        query.setPageNum(0);
        query.setPageSize(999);

        assertThat(query.getPageNum()).isZero();
        assertThat(query.getPageSize()).isEqualTo(999);
        assertThat(query.normalizedPageNum()).isEqualTo(1);
        assertThat(query.normalizedPageSize()).isEqualTo(100);
    }
}
