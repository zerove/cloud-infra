{
  local common = import 'common.libsonnet';

  {
    dashboard: {
      title: 'Cloud-Infra Gateway 监控',
      uid: 'gateway-metrics',
      tags: ['gateway', 'gateway'],
      timezone: 'Asia/Shanghai',
      refresh: '10s',
      time: { from: 'now-1h', to: 'now' },
      panels: [
        common.row('Gateway 概览', [
          common.panel('请求总量 (QPS)', 'timeseries', [
            { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-gateway"}[5m]))', legendFormat: '{{service}}', refId: 'A' },
          ], common.gridPos(8, 12, 0, 0), { unit: 'reqps' }),
          common.panel('错误率', 'gauge', [
            { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-gateway",status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{application="cloud-infra-gateway"}[5m])) * 100', legendFormat: '错误率 %', refId: 'A' },
          ], common.gridPos(4, 6, 0, 4), { 
            unit: 'percent',
            thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:1},{color:'red',value:5}] }
          }),
          common.panel('P99 延迟', 'gauge', [
            { expr: 'histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-gateway"}[5m])) by (le)) * 1000', legendFormat: 'P99 ms', refId: 'A' },
          ], common.gridPos(4, 6, 6, 4), { unit: 'ms' }),
          common.panel('P95 延迟', 'gauge', [
            { expr: 'histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-gateway"}[5m])) by (le)) * 1000', legendFormat: 'P95 ms', refId: 'A' },
          ], common.gridPos(4, 6, 12, 4), { unit: 'ms' }),
        ]),
        
        // 行: 请求分布
        {
          type: 'row',
          title: '请求分布',
          gridPos: { h: 1, w: 24, x: 0, y: 8 },
          collapsed: false,
          panels: [
            {
              title: '状态码分布',
              type: 'piechart',
              gridPos: { h: 12, w: 12, x: 0, y: 8 },
              targets: [
                { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-gateway"}[5m])) by (status)', legendFormat: '{{status}}', refId: 'A' },
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
          ],
        },
        
        // 行: 延迟分布
        {
          type: 'row',
          title: '延迟分布',
          gridPos: { h: 1, w: 24, x: 0, y: 16 },
          collapsed: false,
          panels: [
            {
              title: '延迟热力图',
              type: 'heatmap',
              gridPos: { h: 12, w: 12, x: 0, y: 16 },
              targets: [
                { expr: 'sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-gateway"}[5m])) by (le)', legendFormat: '{{le}}', refId: 'A' },
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
                { expr: 'histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-gateway"}[5m])) by (le)) * 1000', legendFormat: 'P50', refId: 'A' },
                { expr: 'histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-gateway"}[5m])) by (le)) * 1000', legendFormat: 'P95', refId: 'B' },
                { expr: 'histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-gateway"}[5m])) by (le)) * 1000', legendFormat: 'P99', refId: 'C' },
              ],
              fieldConfig: { defaults: { unit: 'ms', lines: true, fillOpacity: 10 } },
            },
          ],
        },
        
        // 行: 吞吐量
        {
          type: 'row',
          title: '吞吐量与错误',
          gridPos: { h: 1, w: 24, x: 0, y: 24 },
          collapsed: false,
          panels: [
            {
              title: '请求吞吐量 (QPS)',
              type: 'timeseries',
              gridPos: { h: 12, w: 12, x: 0, y: 24 },
              targets: [
                { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-gateway"}[5m])) by (status)', legendFormat: '{{status}}', refId: 'A' },
              ],
              fieldConfig: { defaults: { unit: 'reqps' } },
            },
            {
              title: '错误率趋势',
              type: 'timeseries',
              gridPos: { h: 8, w: 12, x: 12, y: 24 },
              targets: [
                { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-gateway",status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{application="cloud-infra-gateway"}[5m])) * 100', legendFormat: '错误率 %', refId: 'A' },
              ],
              fieldConfig: { defaults: { unit: 'percent', thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:1},{color:'red',value:5}] } } },
            },
            {
              title: '慢查询数量',
              type: 'stat',
              gridPos: { h: 4, w: 6, x: 0, y: 24 },
              targets: [
                { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-gateway",status=~"5.."}[5m]))', legendFormat: '5xx/s', refId: 'A' },
              ],
              fieldConfig: { defaults: { thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:10},{color:'red',value:50}] } } },
            },
            {
              title: '慢查询占比',
              type: 'gauge',
              gridPos: { h: 4, w: 6, x: 6, y: 24 },
              targets: [
                { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-gateway",status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{application="cloud-infra-gateway"}[5m])) * 100', legendFormat: '慢查询占比 %', refId: 'A' },
              ],
              fieldConfig: { defaults: { unit: 'percent', thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:5},{color:'red',value:10}] } } },
            },
            {
              title: '活跃连接数',
              type: 'stat',
              gridPos: { h: 4, w: 6, x: 12, y: 24 },
              targets: [
                { expr: 'sum(tomcat_threads_current_threads{application="cloud-infra-gateway"})', legendFormat: '当前线程', refId: 'A' },
              ],
              fieldConfig: { defaults: { thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:150},{color:'red',value:200}] } } },
            },
          ],
        },
        
        // 行: 资源使用
        {
          type: 'row',
          title: '资源使用',
          gridPos: { h: 1, w: 24, x: 0, y: 32 },
          collapsed: false,
          panels: [
            {
              title: 'CPU 使用率',
              type: 'timeseries',
              gridPos: { h: 8, w: 12, x: 0, y: 32 },
              targets: [
                { expr: 'sum(rate(container_cpu_usage_seconds_total{pod=~"cloud-infra-gateway-.*"}[5m])) by (pod)', legendFormat: '{{pod}}', refId: 'A' },
              ],
              fieldConfig: { defaults: { unit: 'cores', lines: true, fillOpacity: 10 } },
            },
            {
              title: '内存使用',
              type: 'timeseries',
              gridPos: { h: 8, w: 12, x: 12, y: 40 },
              targets: [
                { expr: 'container_memory_usage_bytes{pod=~"cloud-infra-gateway-.*"}', legendFormat: '{{pod}}', refId: 'A' },
              ],
              fieldConfig: { defaults: { unit: 'bytes', lines: true, fillOpacity: 10 } },
            },
            {
              title: '网络 I/O',
              type: 'timeseries',
              gridPos: { h: 8, w: 12, x: 0, y: 48 },
              targets: [
                { expr: 'rate(container_network_receive_bytes_total{pod=~"cloud-infra-gateway-.*"}[5m])', legendFormat: '{{pod}} RX', refId: 'A' },
                { expr: 'rate(container_network_transmit_bytes_total{pod=~"cloud-infra-gateway-.*"}[5m])', legendFormat: '{{pod}} TX', refId: 'B' },
              ],
              fieldConfig: { defaults: { unit: 'Bps', lines: true, fillOpacity: 10 } },
            },
            {
              title: '网络连接数',
              type: 'stat',
              gridPos: { h: 4, w: 6, x: 12, y: 48 },
              targets: [
                { expr: 'sum(tcp_connections_established{pod=~"cloud-infra-gateway-.*"})', legendFormat: '连接数', refId: 'A' },
              ],
              fieldConfig: { defaults: { thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:500},{color:'red',value:1000}] } } },
            },
            {
              title: 'GC 频率',
              type: 'timeseries',
              gridPos: { h: 8, w: 12, x: 18, y: 48 },
              targets: [
                { expr: 'rate(jvm_gc_pause_seconds_count{application="cloud-infra-gateway"}[5m])', legendFormat: '{{gc}}', refId: 'A' },
              ],
              fieldConfig: { defaults: { unit: 'ops/s' } },
            },
          ],
        },
      ],
    },
  }
}