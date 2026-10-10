package com.cloudinfra.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.cloudinfra.search.domain.DeviceTelemetryDocument;
import com.cloudinfra.search.domain.OperLogDocument;
import com.cloudinfra.search.dto.IndexDetailVO;
import com.cloudinfra.search.dto.IndexInfoVO;
import com.cloudinfra.search.service.IndexAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.cluster.ClusterHealth;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ES 索引管理实现
 * 列索引通过底层 ElasticsearchClient 获取全量 stats;详情与重建走 Spring Data 高层抽象
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexAdminServiceImpl implements IndexAdminService {

    /** 平台已知业务索引 -> 文档类型映射(重建索引时按 @Document 定义创建) */
    private static final Map<String, Class<?>> KNOWN_INDEXES = Map.of(
            "sys_oper_log", OperLogDocument.class,
            "iot_telemetry", DeviceTelemetryDocument.class
    );

    private final ElasticsearchOperations operations;
    private final ElasticsearchClient elasticsearchClient;

    @Override
    public List<IndexInfoVO> listIndexes() {
        List<IndexInfoVO> result = new ArrayList<>();
        try {
            // 使用 GET /_all 获取索引清单,兼容 ES 7.x/8.x/9.x 服务端
            var getResp = elasticsearchClient.indices().get(g -> g.index("*"));
            getResp.indices().keySet().forEach(name -> {
                IndexInfoVO vo = new IndexInfoVO();
                vo.setIndexName(name);
                vo.setStatus("OPEN");
                result.add(vo);
            });
        } catch (Exception e) {
            log.error("获取 ES 索引列表失败", e);
            throw new IllegalStateException("获取 ES 索引列表失败: " + e.getMessage(), e);
        }
        // 逐个查询文档数(走 Spring Data 高层抽象,协议兼容性最好)
        for (IndexInfoVO vo : result) {
            try {
                vo.setDocCount(operations.count(Query.findAll(), IndexCoordinates.of(vo.getIndexName())));
            } catch (Exception e) {
                log.warn("查询索引 {} 文档数失败: {}", vo.getIndexName(), e.getMessage());
            }
        }
        // 平台已知但尚未创建的索引,标记为 MISSING,便于管理员感知
        for (String name : KNOWN_INDEXES.keySet()) {
            if (result.stream().noneMatch(v -> v.getIndexName().equals(name))) {
                IndexInfoVO vo = new IndexInfoVO();
                vo.setIndexName(name);
                vo.setStatus("MISSING");
                result.add(vo);
            }
        }
        result.sort(Comparator.comparing(IndexInfoVO::getIndexName));
        return result;
    }

    @Override
    public IndexDetailVO getIndexDetail(String indexName) {
        IndexCoordinates coordinates = IndexCoordinates.of(indexName);
        IndexOperations indexOps = operations.indexOps(coordinates);
        IndexDetailVO vo = new IndexDetailVO();
        vo.setIndexName(indexName);
        if (!indexOps.exists()) {
            vo.setMapping(Map.of());
            vo.setSettings(Map.of());
            return vo;
        }
        vo.setDocCount(operations.count(Query.findAll(), coordinates));
        vo.setMapping(indexOps.getMapping());
        vo.setSettings(indexOps.getSettings());
        return vo;
    }

    @Override
    public void rebuildIndex(String indexName) {
        Class<?> docType = KNOWN_INDEXES.get(indexName);
        if (docType == null) {
            throw new IllegalArgumentException(
                    "不支持的索引 '" + indexName + "', 平台已知索引: " + KNOWN_INDEXES.keySet());
        }
        IndexOperations indexOps = operations.indexOps(docType);
        if (indexOps.exists()) {
            log.warn("重建索引 {}: 删除旧索引", indexName);
            indexOps.delete();
            // 删除后 ES 的分片分配器仍在处理旧分片释放, 若立刻 create 则新索引的
            // 主分片可能尚未就绪, 后续 search 阶段会报 no_shard_available_action_exception。
            // 等待索引元数据不再包含旧分片后才重建。
            awaitShardsReleased(docType);
        }
        indexOps.createWithMapping();
        log.info("索引 {} 已重建(数据已清空)", indexName);
    }

    /**
     * 等待 ES 报告目标索引已无分片处于分配中状态。
     *
     * <p>用于 delete-then-create 之间的就绪等待。轮询而非固定 sleep, 因为释放时间随
     * 宿主负载变化, 固定 sleep 在负载高时会不够、在负载低时浪费时间。
     */
    private void awaitShardsReleased(Class<?> docType) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            ClusterHealth health = operations.cluster().health();
            if (health.getRelocatingShards() == 0 && health.getInitializingShards() == 0) {
                return;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待索引 " + docType.getSimpleName() + " 分片释放被中断", e);
            }
        }
        log.warn("等待索引 {} 分片释放超时, 继续重建", docType.getSimpleName());
    }

    @Override
    public void deleteIndex(String indexName) {
        IndexCoordinates coordinates = IndexCoordinates.of(indexName);
        IndexOperations indexOps = operations.indexOps(coordinates);
        if (!indexOps.exists()) {
            throw new IllegalArgumentException("索引 '" + indexName + "' 不存在");
        }
        indexOps.delete();
        log.info("索引 {} 已删除", indexName);
    }
}
