package com.cloudinfra.search.service.impl;

import com.cloudinfra.api.search.dto.SearchRequest;
import com.cloudinfra.api.search.dto.SearchResponse;
import com.cloudinfra.api.search.dto.OperLogDTO;
import com.cloudinfra.search.repository.OperLogRepository;
import com.cloudinfra.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final OperLogRepository operLogRepository;

    @Override
    public SearchResponse<OperLogDTO> searchOperLog(SearchRequest request) {
        log.info("搜索操作日志: {}", request.getKeyword());
        return new SearchResponse<>();
    }
}
