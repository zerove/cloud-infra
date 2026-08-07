package com.cloudinfra.search.service;

import com.cloudinfra.search.dto.IndexDetailVO;
import com.cloudinfra.search.dto.IndexInfoVO;

import java.util.List;

/**
 * ES 索引管理服务
 * 提供索引列表、详情、重建与删除等运维能力
 */
public interface IndexAdminService {

    /**
     * 列出全部索引(含平台已知但尚未创建的索引)
     */
    List<IndexInfoVO> listIndexes();

    /**
     * 查询索引详情(mapping、settings、文档数)
     */
    IndexDetailVO getIndexDetail(String indexName);

    /**
     * 重建索引: 仅支持平台已知业务索引,删除后按 @Document 定义重新创建,数据清空
     */
    void rebuildIndex(String indexName);

    /**
     * 删除索引
     */
    void deleteIndex(String indexName);
}
