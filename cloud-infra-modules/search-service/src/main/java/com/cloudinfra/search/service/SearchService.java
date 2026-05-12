package com.cloudinfra.search.service;

import com.cloudinfra.api.search.dto.SearchRequest;
import com.cloudinfra.api.search.dto.SearchResponse;
import com.cloudinfra.api.search.dto.OperLogDTO;

/**
 * 搜索服务接口
 * 提供对操作日志等数据的全文搜索功能
 */
public interface SearchService {
    /**
     * 分页搜索操作日志
     *
     * @param request 搜索请求，包含关键词、页码和页大小
     * @return 包含搜索结果及总条数的响应对象
     */
    SearchResponse<OperLogDTO> searchOperLog(SearchRequest request);
}
