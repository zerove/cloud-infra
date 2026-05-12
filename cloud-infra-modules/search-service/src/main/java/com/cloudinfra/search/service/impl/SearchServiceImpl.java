package com.cloudinfra.search.service.impl;

import com.cloudinfra.api.search.dto.*;
import com.cloudinfra.search.domain.OperLogDocument;
import com.cloudinfra.search.repository.OperLogRepository;
import com.cloudinfra.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 搜索服务实现类
 * 协调 Elasticsearch 仓库实现操作日志的分页搜索
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final OperLogRepository operLogRepository;

    /**
     * 分页搜索操作日志
     * <p>
     * 逻辑：
     * 1. 将前端传入的 1-based 页码转换为 Spring Data 的 0-based 页码
     * 2. 调用 OperLogRepository 进行关键词搜索
     * 3. 将搜索到的 OperLogDocument 映射为 OperLogDTO 并返回
     * </p>
     *
     * @param request 搜索请求
     * @return 搜索结果响应
     */
    @Override
    public SearchResponse<OperLogDTO> searchOperLog(SearchRequest request) {
        log.info("Executing operlog search: keyword={}, page={}, size={}", 
            request.getKeyword(), request.getPage(), request.getSize());
        
        // SearchRequest 中的 page 是从 1 开始的，而 PageRequest 是从 0 开始的
        int page = (request.getPage() != null && request.getPage() > 0) ? request.getPage() - 1 : 0;
        int size = (request.getSize() != null && request.getSize() > 0) ? request.getSize() : 10;
        Pageable pageable = PageRequest.of(page, size);
        
        Page<OperLogDocument> pageResult = operLogRepository.findByKeyword(request.getKeyword(), pageable);
        
        SearchResponse<OperLogDTO> response = new SearchResponse<>();
        response.setTotal(pageResult.getTotalElements());
        response.setData(pageResult.getContent().stream()
            .map(this::convertToDto)
            .collect(Collectors.toList()));
            
        return response;
    }

    /**
     * 将 Elasticsearch 文档对象转换为 API 传输对象
     *
     * @param doc 搜索结果文档
     * @return 操作日志 DTO
     */
    private OperLogDTO convertToDto(OperLogDocument doc) {
        OperLogDTO dto = new OperLogDTO();
        dto.setId(doc.getOperId());
        dto.setTitle(doc.getTitle());
        dto.setUserName(doc.getOperName());
        dto.setCreateTime(doc.getOperTime());
        dto.setMethod(doc.getMethod());
        dto.setIp(doc.getOperIp());
        return dto;
    }
}
