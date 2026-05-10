package com.cloudinfra.common.core.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用分页返回对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /**
     * 数据列表
     */
    private List<T> list;

    /**
     * 总记录数
     */
    private Long total;

    public static <T> PageResult<T> of(List<T> list, Long total) {
        return new PageResult<>(list, total);
    }
}
