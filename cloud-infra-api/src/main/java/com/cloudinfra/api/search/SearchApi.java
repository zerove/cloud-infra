package com.cloudinfra.api.search;

import com.cloudinfra.api.search.dto.*;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "search-service")
public interface SearchApi {
    @GetMapping("/search/operlog")
    Result<SearchResponse<Object>> searchOperLog(@RequestParam String keyword, 
                                                 @RequestParam(defaultValue = "0") int page, 
                                                 @RequestParam(defaultValue = "10") int size);
}
