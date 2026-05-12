package com.cloudinfra.api.search.dto;

import lombok.Data;
import java.io.Serializable;

/**
 * 搜索请求参数
 */
@Data
public class SearchRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 搜索关键词
     */
    private String keyword;

    /**
     * 页码 (从0开始)
     */
    private Integer page;

    /**
     * 每页条数
     */
    private Integer size;
}
