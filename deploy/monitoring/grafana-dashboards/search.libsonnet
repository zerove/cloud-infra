{
  local common = import 'common.libsonnet';

  {
    dashboard: {
      title: 'Cloud-Infra Search Service 监控',
      uid: 'search-metrics',
      tags: ['search', 'elasticsearch'],
      timezone: 'Asia/Shanghai',
      refresh: '10s',
      time: { from: 'now-1h', to: 'now' },
      panels: [
        common.row('Search Service 概览', [
          common.panel('搜索请求量', 'timeseries', [
            { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-search"}[5m]))', legendFormat: '{{uri}}', refId: 'A' },
          ], common.gridPos(8, 12, 0, 0), { unit: 'reqps' }),
          common.panel('搜索成功率', 'gauge', [
            { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-search",status=~"2.."}[5m])) / sum(rate(http_server_requests_seconds_count{application="cloud-infra-search"}[5m])) * 100', legendFormat: '成功率 %', refId: 'A' },
          ], common.gridPos(4, 6, 0, 4), { unit: 'percent', thresholds: { steps: [{color:'green',value:99},{color:'yellow',value:95},{color:'red',value:90}] } }),
          common.panel('搜索延迟 P99', 'gauge', [
            { expr: 'histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-search"}[5m])) by (le)) * 1000', legendFormat: 'P99 ms', refId: 'A' },
          ], common.gridPos(4, 6, 6, 4), { unit: 'ms' }),
          common.panel('搜索 QPS', 'gauge', [
            { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-search"}[5m]))', legendFormat: 'QPS', refId: 'A' },
          ], common.gridPos(4, 6, 12, 4), { unit: 'reqps' }),
        ]),
        
        common.row('搜索性能', [
          {
            title: '搜索延迟分布',
            type: 'heatmap',
            gridPos: { h: 12, w: 12, x: 0, y: 8 },
            targets: [
              { expr: 'sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-search"}[5m])) by (le)', legendFormat: '{{le}}', refId: 'A' },
            ],
            options: { calculate: true, xBucketBound: 'le' },
          },
          {
            title: 'P50/P95/P99 延迟趋势',
            type: 'timeseries',
            gridPos: { h: 12, w: 12, x: 12, y: 8 },
            targets: [
              { expr: 'histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-search"}[5m])) by (le)) * 1000', legendFormat: 'P50', refId: 'A' },
              { expr: 'histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-search"}[5m])) by (le)) * 1000', legendFormat: 'P95', refId: 'B' },
              { expr: 'histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-search"}[5m])) by (le)) * 1000', legendFormat: 'P99', refId: 'C' },
            ],
            fieldConfig: { defaults: { unit: 'ms', lines: true, fillOpacity: 10 } },
          },
        ]),
        
        common.row('Elasticsearch 集群', [
          {
            title: '集群健康状态',
            type: 'stat',
            gridPos: { h: 4, w: 6, x: 0, y: 8 },
            targets: [
              { expr: 'elasticsearch_cluster_health_status{status="green"}', legendFormat: '绿色', refId: 'A' },
              { expr: 'elasticsearch_cluster_health_status{status="yellow"}', legendFormat: '黄色', refId: 'B' },
              { expr: 'elasticsearch_cluster_health_status{status="red"}', legendFormat: '红色', refId: 'C' },
            ],
            fieldConfig: { defaults: { thresholds: { steps: [{color:'green',value:1},{color:'yellow',value:0.5},{color:'red',value:0}] } } },
          },
          {
            title: '索引总数',
            type: 'stat',
            gridPos: { h: 4, w: 6, x: 6, y: 8 },
            targets: [
              { expr: 'elasticsearch_indices_count', legendFormat: '索引数', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'short' } },
          },
          {
            title: '文档总数',
            type: 'stat',
            gridPos: { h: 4, w: 6, x: 12, y: 8 },
            targets: [
              { expr: 'elasticsearch_indices_docs_total', legendFormat: '文档数', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'short' } },
          },
          {
            title: '存储大小',
            type: 'stat',
            gridPos: { h: 4, w: 6, x: 18, y: 8 },
            targets: [
              { expr: 'elasticsearch_cluster_store_size_bytes / 1024 / 1024 / 1024', legendFormat: '存储 GB', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'decbytes' } },
          },
        ]),
        
        common.row('索引性能', [
          {
            title: '索引写入速率',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 0, y: 16 },
            targets: [
              { expr: 'rate(elasticsearch_indices_indexing_index_total[5m])', legendFormat: '{{index}}', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'ops/s', lines: true, fillOpacity: 10 } },
          },
          {
            title: '搜索查询速率',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 12, y: 16 },
            targets: [
              { expr: 'rate(elasticsearch_indices_search_query_total[5m])', legendFormat: '{{index}}', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'reqps', lines: true, fillOpacity: 10 } },
          },
          {
            title: '段合并耗时',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 0, y: 16 },
            targets: [
              { expr: 'rate(elasticsearch_indices_merges_total_time_seconds_total[5m])', legendFormat: '{{index}}', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 's', lines: true, fillOpacity: 10 } },
          },
          {
            title: '刷新耗时',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 12, y: 16 },
            targets: [
              { expr: 'rate(elasticsearch_indices_refresh_total_time_seconds_total[5m])', legendFormat: '{{index}}', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 's', lines: true, fillOpacity: 10 } },
          },
        ]),
        
        common.row('集群资源', [
          {
            title: 'JVM 堆内存',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 0, y: 24 },
            targets: [
              { expr: 'jvm_memory_used_bytes{area="heap",application="cloud-infra-search"} / jvm_memory_max_bytes{area="heap",application="cloud-infra-search"} * 100', legendFormat: '堆内存 %', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'percent', lines: true, fillOpacity: 10, thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:70},{color:'red',value:85}] } } },
          },
          {
            title: '线程池队列',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 12, y: 24 },
            targets: [
              { expr: 'elasticsearch_thread_pool_queue{type="search"}', legendFormat: '搜索队列', refId: 'A' },
              { expr: 'elasticsearch_thread_pool_queue{type="write"}', legendFormat: '写入队列', refId: 'B' },
              { expr: 'elasticsearch_thread_pool_queue{type="get"}', legendFormat: '读取队列', refId: 'C' },
            ],
            fieldConfig: { defaults: { unit: 'short', lines: true, fillOpacity: 10 } },
          },
        ]),
      ],
    }
  }
}