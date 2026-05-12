package com.cloudinfra.api.search.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * 搜索响应结果通用包装类
 * @param <T> 结果数据的类型
 */
@Data
public class SearchResponse<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 数据列表
     */
    private List<T> data;

    /**
     * 总记录数
     */
    private long total;
}
