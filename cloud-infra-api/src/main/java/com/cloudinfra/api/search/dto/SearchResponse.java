package com.cloudinfra.api.search.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 搜索响应结果通用包装类
 * @param <T> 结果数据的类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponse<T> {

    /**
     * 数据列表
     */
    private List<T> data;

    /**
     * 总记录数
     */
    private long total;
}
