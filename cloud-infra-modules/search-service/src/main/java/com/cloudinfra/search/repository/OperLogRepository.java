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
    
    @Query("{\"bool\": {\"should\": [{\"match\": {\"title\": \"?0\"}}, {\"match\": {\"operName\": \"?0\"}}, {\"match\": {\"method\": \"?0\"}}, {\"match\": {\"operUrl\": \"?0\"}}]}}")
    Page<OperLogDocument> findByKeyword(String keyword, Pageable pageable);
}
