package com.cloudinfra.api.search;

import com.cloudinfra.api.search.dto.*;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
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
     * @param request 搜索请求参数
     * @return 操作日志分页结果
     */
    @GetMapping("/search/operlog")
    Result<SearchResponse<OperLogDTO>> searchOperLog(@SpringQueryMap SearchRequest request);

}
