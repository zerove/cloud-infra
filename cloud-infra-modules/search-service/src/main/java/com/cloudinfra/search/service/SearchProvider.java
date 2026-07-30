package com.cloudinfra.search.service;

import com.cloudinfra.search.domain.UnifiedSearchResult;
import java.util.List;
import java.util.Map;

/**
 * 搜索提供者接口 - 定义具体资源索引的检索行为
 */
public interface SearchProvider {
    /**
     * 执行搜索
     * @param query 搜索关键词
     * @param limit 每个提供者的最大返回结果数
     * @return 标准化的结果列表
     */
    List<UnifiedSearchResult> search(String query, int limit);

    /**
     * 获取该提供者的资源类型标识
     */
    String getResourceType();

    /**
     * 获取该提供者的优先级/权重 (用于结果排序)
     */
    default double getWeight() {
        return 1.0;
    }
}