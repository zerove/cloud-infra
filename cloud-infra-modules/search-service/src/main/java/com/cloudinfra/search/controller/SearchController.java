package com.cloudinfra.search.controller;

import com.cloudinfra.api.search.*;
import com.cloudinfra.api.search.dto.*;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

/**
 * 搜索管理控制器
 * 提供对操作日志等系统数据的搜索接口实现
 */
@Tag(name = "搜索管理")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController implements SearchApi {

    private final SearchService searchService;

    /**
     * 搜索操作日志
     * 
     * @param request 搜索请求参数，包含关键字、页码和页大小
     * @return 包含搜索结果的分页响应
     */
    @Override
    @Operation(summary = "搜索操作日志")
    @GetMapping("/operlog")
    public Result<SearchResponse<OperLogDTO>> searchOperLog(@SpringQueryMap SearchRequest request) {
        return Result.success(searchService.searchOperLog(request));
    }
}
