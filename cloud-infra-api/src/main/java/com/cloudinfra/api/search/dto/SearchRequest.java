package com.cloudinfra.api.search.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 生产级搜索请求 DTO
 */
@Data
public class SearchRequest {
    private String keyword;            // 全文检索关键字
    private Long userId;               // 过滤用户ID
    private String category;           // 过滤类别
    private String startTime;          // 开始时间 (ISO format)
    private String endTime;            // 结束时间 (ISO format)
    private Integer pageNum = 1;       // 页码
    private Integer pageSize = 10;     // 每页大小
    private String sortBy = "createTime"; // 排序字段
    private boolean ascending = false;  // 排序方向
    
    // 允许传递自定义的过滤参数
    private Map<String, Object> filters;
}
