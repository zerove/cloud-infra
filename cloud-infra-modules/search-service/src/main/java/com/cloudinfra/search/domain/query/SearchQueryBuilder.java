package com.cloudinfra.search.domain.query;

import org.springframework.stereotype.Component;

/**
 * 搜索查询构建器 - 深层接缝的实现
 * 将业务查询意图转化为底层存储特定的 DSL
 */
@Component
public class SearchQueryBuilder {

    /**
     * 构建操作日志的全文搜索 JSON
     */
    public String buildOperLogKeywordQuery(OperLogQuery query) {
        return String.format(
            "{\"bool\": {\"must\": [{\"multi_match\": {\"query\": \"?0\", \"fields\": [\"title^%d\", \"operName^%d\", \"method^%d\", \"operUrl^%d\"]}}]}}",
            query.titleWeight(),
            query.operNameWeight(),
            query.methodWeight(),
            query.operUrlWeight()
        );
    }
}
