package com.cloudinfra.common.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageResult} 构造与转换契约 (2026-10-18, 报告 A1)。
 *
 * <p>关键契约: {@code map} 必须保留 total 与页面窗口 —— 这与 MyBatis-Plus
 * {@code IPage} 的转换是两条容易分叉的路径, 若 map 丢窗口,
 * 消费方 (如审计聚合) 翻页判断会静默失效。</p>
 */
class PageResultTest {

    @Test
    @DisplayName("of 完整携带 records/total/页面窗口")
    void ofCarriesAllFields() {
        PageResult<String> result = PageResult.of(List.of("a", "b"), 42L, 3L, 10L);

        assertThat(result.getRecords()).containsExactly("a", "b");
        assertThat(result.getTotal()).isEqualTo(42L);
        assertThat(result.getPageNum()).isEqualTo(3L);
        assertThat(result.getPageSize()).isEqualTo(10L);
    }

    @Test
    @DisplayName("empty: records 为空列表, total 与页面窗口均为 0")
    void emptyHasZeroTotals() {
        PageResult<String> result = PageResult.empty();

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(result.getPageNum()).isZero();
        assertThat(result.getPageSize()).isZero();
    }

    @Test
    @DisplayName("map 转换记录类型并保留 total 与页面窗口")
    void mapPreservesTotalsAndWindow() {
        PageResult<String> source = PageResult.of(List.of("aa", "bbb"), 7L, 2L, 5L);

        PageResult<Integer> mapped = source.map(String::length);

        assertThat(mapped.getRecords()).containsExactly(2, 3);
        assertThat(mapped.getTotal()).isEqualTo(7L);
        assertThat(mapped.getPageNum()).isEqualTo(2L);
        assertThat(mapped.getPageSize()).isEqualTo(5L);
    }

    @Test
    @DisplayName("map 作用在 empty 上仍为空且窗口保持为 0")
    void mapOnEmptyStaysEmpty() {
        PageResult<Integer> mapped = PageResult.<String>empty().map(String::length);

        assertThat(mapped.getRecords()).isEmpty();
        assertThat(mapped.getTotal()).isZero();
        assertThat(mapped.getPageNum()).isZero();
        assertThat(mapped.getPageSize()).isZero();
    }

    @Test
    @DisplayName("默认构造的 records 是空列表而非 null (反序列化友好)")
    void defaultRecordsIsEmptyList() {
        PageResult<String> result = new PageResult<>();

        assertThat(result.getRecords()).isNotNull().isEmpty();
    }
}
