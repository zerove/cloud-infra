{
  local common = import 'common.libsonnet';

  {
    dashboard: {
      title: 'Cloud-Infra System Service 监控',
      uid: 'system-metrics',
      tags: ['system', 'service'],
      timezone: 'Asia/Shanghai',
      refresh: '10s',
      time: { from: 'now-1h', to: 'now' },
      panels: [
        common.row('System Service 概览', [
          common.panel('请求总量 (QPS)', 'timeseries', [
            { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-system"}[5m]))', legendFormat: '{{service}}', refId: 'A' },
          ], common.gridPos(8, 12, 0, 0), { unit: 'reqps' }),
          common.panel('错误率', 'gauge', [
            { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-system",status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{application="cloud-infra-system"}[5m])) * 100', legendFormat: '错误率 %', refId: 'A' },
          ], common.gridPos(4, 6, 0, 4), { 
            unit: 'percent',
            thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:1},{color:'red',value:5}] }
          }),
          common.panel('P99 延迟', 'gauge', [
            { expr: 'histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-system"}[5m])) by (le)) * 1000', legendFormat: 'P99 ms', refId: 'A' },
          ], common.gridPos(4, 6, 6, 4), { unit: 'ms' }),
          common.panel('P95 延迟', 'gauge', [
            { expr: 'histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-system"}[5m])) by (le)) * 1000', legendFormat: 'P95 ms', refId: 'A' },
          ], common.gridPos(4, 6, 12, 4), { unit: 'ms' }),
        ]),
        
        common.row('请求分布', [
          {
            title: '状态码分布',
            type: 'piechart',
            gridPos: { h: 12, w: 12, x: 0, y: 8 },
            targets: [
              { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-system"}[5m])) by (status)', legendFormat: '{{status}}', refId: 'A' },
            ],
            options: { displayMode: 'list' },
            fieldConfig: { defaults: { unit: 'short' } },
          },
          {
            title: 'API 端点分布',
            type: 'piechart',
            gridPos: { h: 12, w: 12, x: 12, y: 8 },
            targets: [
              { expr: 'sum(rate(http_server_requests_seconds_count[5m])) by (uri)', legendFormat: '{{uri}}', refId: 'A' },
            ],
            options: { displayMode: 'list' },
            fieldConfig: { defaults: { unit: 'short' } },
          },
        ]),
        
        common.row('延迟分布', [
          {
            title: '延迟热力图',
            type: 'heatmap',
            gridPos: { h: 12, w: 12, x: 0, y: 16 },
            targets: [
              { expr: 'sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-system"}[5m])) by (le)', legendFormat: '{{le}}', refId: 'A' },
            ],
            options: {
              calculate: true,
              xBucketBound: 'le',
            },
          },
          {
            title: 'P50/P95/P99 延迟趋势',
            type: 'timeseries',
            gridPos: { h: 12, w: 12, x: 12, y: 16 },
            targets: [
              { expr: 'histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-system"}[5m])) by (le)) * 1000', legendFormat: 'P50', refId: 'A' },
              { expr: 'histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-system"}[5m])) by (le)) * 1000', legendFormat: 'P95', refId: 'B' },
              { expr: 'histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-system"}[5m])) by (le)) * 1000', legendFormat: 'P99', refId: 'C' },
            ],
            fieldConfig: { defaults: { unit: 'ms', lines: true, fillOpacity: 10 } },
          },
        ]),
        
        common.row('资源使用', [
          {
            title: 'CPU 使用率',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 0, y: 16 },
            targets: [
              { expr: 'sum(rate(container_cpu_usage_seconds_total{pod=~"cloud-infra-system-.*"}[5m])) by (pod)', legendFormat: '{{pod}}', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'cores', lines: true, fillOpacity: 10 } },
          },
          {
            title: '内存使用',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 12, y: 16 },
            targets: [
              { expr: 'container_memory_usage_bytes{pod=~"cloud-infra-system-.*"}', legendFormat: '{{pod}}', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'bytes', lines: true, fillOpacity: 10 } },
          },
        ]),
      ],
    }
  }
}