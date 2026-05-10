package com.cloudinfra.search.repository;

import com.cloudinfra.search.domain.OperLogDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * 操作日志 ES 仓储
 */
@Repository
public interface OperLogRepository extends ElasticsearchRepository<OperLogDocument, Long> {
}
