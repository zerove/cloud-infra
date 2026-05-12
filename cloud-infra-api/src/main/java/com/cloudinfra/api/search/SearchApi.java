package com.cloudinfra.api.search;

import com.cloudinfra.api.search.dto.OperLogDTO;
import com.cloudinfra.api.search.dto.SearchRequest;
import com.cloudinfra.api.search.dto.SearchResponse;
import com.cloudinfra.common.core.domain.Result;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 搜索服务 API 客户端，增加熔断降级策略
 */
@FeignClient(name = "search-service", fallbackFactory = SearchApiFallbackFactory.class)
public interface SearchApi {

    /**
     * 搜索操作日志
     */
    @GetMapping("/search/operlog")
    @CircuitBreaker(name = "searchService")
    Result<SearchResponse<OperLogDTO>> searchOperLog(@SpringQueryMap SearchRequest request);
}
