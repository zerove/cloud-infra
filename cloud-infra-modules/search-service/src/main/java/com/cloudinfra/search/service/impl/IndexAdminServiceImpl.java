package com.cloudinfra.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.cluster.health.IndexHealthStats;
import com.cloudinfra.search.domain.DeviceTelemetryDocument;
import com.cloudinfra.search.domain.OperLogDocument;
import com.cloudinfra.search.dto.IndexDetailVO;
import com.cloudinfra.search.dto.IndexInfoVO;
import com.cloudinfra.search.service.IndexAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
            awaitShardsReleased(indexName, docType);
        }
        indexOps.createWithMapping();
        log.info("索引 {} 已重建(数据已清空)", indexName);
    }

    /**
     * 等待 ES 报告目标索引已无分片处于分配中状态。
     *
     * <p>用于 delete-then-create 之间的就绪等待。轮询而非固定 sleep, 因为释放时间随
     * 宿主负载变化, 固定 sleep 在负载高时会不够、在负载低时浪费时间。
     *
     * <p><b>判据必须绑定到目标索引</b>。早期版本只读 {@code operations.cluster().health()}
     * 并检查集群级的 {@code relocatingShards}/{@code initializingShards}, 但 Spring Data 的
     * {@code ClusterOperations.health()} 无参、只能给集群级视图, 且方法签名不接收索引名 ——
     * {@code docType} 到这里已丢失索引标识。于是"集群整体干净"会掩盖"本索引仍在分配中",
     * 判据与被等待的对象之间没有任何因果绑定。
     * {@code RebuildReadinessGuardTest#readinessCriterionIsBoundToTargetIndex} 对该版本报红即为此。
     *
     * <p>现改为直接查询目标索引的 {@link IndexHealthStats}, 并额外要求它已不在集群
     * {@code indices} 视图中出现(即旧分片完全从分配器移除)。索引名同时出现在
     * {@code HealthRequest} 与日志/异常里, 便于失败时定位。
     *
     * <p><b>⚠️ 因果有效性尚未证实</b>(2026-10-11 实测): 把本判据退回集群级形态后重跑
     * {@code SearchServiceIntegrationTest}, 仍是 <b>8/8 全绿、0 次 no_shard_available</b> ——
     * 即原 503 在单容器条件下<b>无法复现</b>。因此可以确认的是"集群级判据在原理上无法绑定目标索引"
     * (源码守护 {@code readinessCriterionIsBoundToTargetIndex} 对其报红), 但<b>不能</b>声称
     * "本修复消除了该 503"。这与 {@code beforeEachDeleteAllIsNotBare} 当初被
     * {@code @Disabled} 的理由一致。闭环需要宿主资源竞争下的复现证据。
     */
    private void awaitShardsReleased(String indexName, Class<?> docType) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            boolean released;
            try {
                // 索引级释放判据(内联在此方法内, 不得抽到别处:
                // RebuildReadinessGuardTest 只检查本方法体, 抽出去会让守护失去被守护对象):
                // 目标索引要么已从集群视图消失, 要么其自身无分配中/迁移中分片且主分片全部 active。
                HealthResponse health = elasticsearchClient.cluster().health(b -> b.index(indexName));
                IndexHealthStats stats = health.indices().get(indexName);
                released = stats == null
                        || (stats.initializingShards() == 0
                                && stats.relocatingShards() == 0
                                && stats.activePrimaryShards() >= stats.numberOfShards());
            } catch (IOException e) {
                // 等待期内的 ES 连接抖动不是"分片未释放", 也不该中断重建。
                // 这里继续轮询直至 deadline; 超时后走下面的 warn 分支,
                // 行为与旧实现(到点仍不等)一致, 不会把一次抖动放大成硬失败。
                log.debug("查询索引 {} 健康状态失败, 继续等待: {}", indexName, e.getMessage());
                released = false;
            }
            if (released) {
                return;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待索引 " + indexName + " 分片释放被中断", e);
            }
        }
        log.warn("等待索引 {} ({}) 分片释放超时, 继续重建", indexName, docType.getSimpleName());
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
