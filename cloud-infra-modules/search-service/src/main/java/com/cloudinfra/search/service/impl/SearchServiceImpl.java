package com.cloudinfra.search.service.impl;
// CANARY_V2

import com.cloudinfra.api.search.dto.SearchRequest;
import com.cloudinfra.api.search.dto.SearchResponse;
import com.cloudinfra.api.search.dto.OperLogDTO;
import com.cloudinfra.search.domain.query.OperLogQuery;
import com.cloudinfra.search.domain.query.SearchQueryBuilder;
import com.cloudinfra.search.repository.OperLogRepository;
import com.cloudinfra.search.domain.OperLogDocument;
import com.cloudinfra.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final OperLogRepository operLogRepository;
    private final SearchQueryBuilder queryBuilder;

    @Override
    public SearchResponse<OperLogDTO> searchOperLog(SearchRequest request) {
        log.info("搜索操作日志: {}", request.getKeyword());
        
        // 1. 构建领域查询对象 (Deepening: 将业务意图抽象化)
        OperLogQuery query = OperLogQuery.defaultQuery(request.getKeyword());
        
        // 2. 执行搜索 (Repository 承担基础设施执行)
        Page<OperLogDocument> page = operLogRepository.findByKeyword(
            query.getKeyword(), 
            PageRequest.of(request.getPage() - 1, request.getSize())
        );

        // 3. 转换为 DTO (确保 ES 类型不泄露至 Controller)
        List<OperLogDTO> list = page.getContent().stream()
            .map(doc -> {
                OperLogDTO dto = new OperLogDTO();
                dto.setOperId(doc.getOperId());
                dto.setTitle(doc.getTitle());
                dto.setBusinessType(String.valueOf(doc.getBusinessType()));
                dto.setMethod(doc.getMethod());
                dto.setOperName(doc.getOperName());
                dto.setOperUrl(doc.getOperUrl());
                dto.setOperIp(doc.getOperIp());
                dto.setOperParam(doc.getOperParam());
                dto.setJsonResult(doc.getJsonResult());
                dto.setStatus(doc.getStatus());
                dto.setErrorMsg(doc.getErrorMsg());
                dto.setOperTime(doc.getOperTime());
                return dto;
            })
            .collect(Collectors.toList());

        return new SearchResponse<>(list, page.getTotalElements());
    }
}
