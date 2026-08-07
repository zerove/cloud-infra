package com.cloudinfra.search.dto;

import lombok.Data;

/**
 * ES 索引概要信息
 */
@Data
public class IndexInfoVO {
    /** 索引名称 */
    private String indexName;
    /** 文档数量 */
    private long docCount;
    /** 索引状态: OPEN / MISSING */
    private String status;
}
