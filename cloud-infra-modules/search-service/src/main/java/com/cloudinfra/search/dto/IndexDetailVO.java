package com.cloudinfra.search.dto;

import lombok.Data;

import java.util.Map;

/**
 * ES 索引详情: mapping 与 settings
 */
@Data
public class IndexDetailVO {
    /** 索引名称 */
    private String indexName;
    /** 文档数量 */
    private long docCount;
    /** 索引 mapping 定义 */
    private Map<String, Object> mapping;
    /** 索引 settings 定义 */
    private Map<String, Object> settings;

    // Manual getters/setters for Lombok compatibility
    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public long getDocCount() { return docCount; }
    public void setDocCount(long docCount) { this.docCount = docCount; }
    public Map<String, Object> getMapping() { return mapping; }
    public void setMapping(Map<String, Object> mapping) { this.mapping = mapping; }
    public Map<String, Object> getSettings() { return settings; }
    public void setSettings(Map<String, Object> settings) { this.settings = settings; }
}
