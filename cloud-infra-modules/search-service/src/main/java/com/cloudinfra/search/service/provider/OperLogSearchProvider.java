package com.cloudinfra.search.service.provider;

import com.cloudinfra.search.domain.UnifiedSearchResult;
import com.cloudinfra.search.repository.OperLogRepository;
import com.cloudinfra.search.service.SearchProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class OperLogSearchProvider implements SearchProvider {

    private final OperLogRepository operLogRepository;

    @Override
    public List<UnifiedSearchResult> search(String query, int limit) {
        return operLogRepository.findByKeyword(query, PageRequest.of(0, limit))
            .getContent()
            .stream()
            .map(doc -> new UnifiedSearchResult(
                doc.getOperId().toString(),
                getResourceType(),
                doc.getTitle(),
                doc.getOperName() + " - " + doc.getMethod(),
                1.0,
                doc.getCreateTime(),
                "/system/logs/" + doc.getOperId()
            ))
            .collect(Collectors.toList());
    }

    @Override
    public String getResourceType() {
        return "OPER_LOG";
    }

    @Override
    public double getWeight() {
        return 0.8;
    }
}