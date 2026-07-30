package com.cloudinfra.search.domain.query;

/**
 * 操作日志搜索查询定义
 * 将业务搜索意图与底层 ES 语法解耦
 */
public record OperLogQuery(
    String keyword,
    Integer titleWeight,
    Integer operNameWeight,
    Integer methodWeight,
    Integer operUrlWeight
) {
    public static OperLogQuery defaultQuery(String keyword) {
        return new OperLogQuery(
            keyword,
            3,
            2,
            1,
            1
        );
    }
}
