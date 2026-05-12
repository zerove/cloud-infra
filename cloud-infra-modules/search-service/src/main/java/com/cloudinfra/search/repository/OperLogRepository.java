package com.cloudinfra.search.repository;

import com.cloudinfra.search.domain.OperLogDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.stereotype.Repository;

/**
 * 操作日志 ES 仓储
 */
@Repository
public interface OperLogRepository extends ElasticsearchRepository<OperLogDocument, Long> {
    
    @Query("{\"bool\": {\"must\": [{\"multi_match\": {\"query\": \"?0\", \"fields\": [\"title^3\", \"operName^2\", \"method^1\", \"operUrl^1\"]}}]}}")
    Page<OperLogDocument> findByKeyword(String keyword, Pageable pageable);
}
