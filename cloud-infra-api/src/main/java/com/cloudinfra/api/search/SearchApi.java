package com.cloudinfra.api.search;

import com.cloudinfra.api.search.dto.*;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 搜索服务 API 客户端
 */
@FeignClient(name = "search-service")
public interface SearchApi {

    /**
     * 搜索操作日志
     * 
     * @param keyword 搜索关键词
     * @param page 页码 (从0开始)
     * @param size 每页条数
     * @return 操作日志列表结果
     */
    @GetMapping("/search/operlog")
    Result<List<?>> searchOperLog(@RequestParam("keyword") String keyword, 
                                 @RequestParam(value = "page", defaultValue = "0") Integer page, 
                                 @RequestParam(value = "size", defaultValue = "10") Integer size);
}
