package com.cloudinfra.api.search;

import com.cloudinfra.api.search.dto.*;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.cloud.openfeign.SpringQueryMap;

@FeignClient(name = "search-service")
public interface SearchApi {
    @GetMapping("/search/operlog")
    Result<SearchResponse<OperLogDTO>> searchOperLog(@SpringQueryMap SearchRequest request);
}
