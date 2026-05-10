package com.cloudinfra.common.core.domain;

import lombok.Data;

/**
 * 通用分页请求对象
 */
@Data
public class PageDTO {

    /**
     * 当前页码，默认 1
     */
    private Long current = 1L;

    /**
     * 每页大小，默认 10
     */
    private Long size = 10L;
    
    /**
     * 排序字段 (默认无需排序可以为空)
     */
    private String orderByColumn;
    
    /**
     * 是否升序 (默认 true)
     */
    private Boolean isAsc = true;
}
