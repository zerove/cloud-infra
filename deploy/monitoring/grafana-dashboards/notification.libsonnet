{
  local common = import 'common.libsonnet';

  {
    dashboard: {
      title: 'Cloud-Infra Notification Service 监控',
      uid: 'notification-metrics',
      tags: ['notification', 'messaging'],
      timezone: 'Asia/Shanghai',
      refresh: '10s',
      time: { from: 'now-1h', to: 'now' },
      panels: [
        common.row('Notification Service 概览', [
          common.panel('通知发送量', 'timeseries', [
            { expr: 'sum(rate(notification_sent_total[5m])) by (type)', legendFormat: '{{type}}', refId: 'A' },
          ], common.gridPos(8, 12, 0, 0), { unit: 'reqps' }),
          common.panel('发送成功率', 'gauge', [
            { expr: 'sum(rate(notification_sent_total{status="success"}[5m])) / sum(rate(notification_sent_total[5m])) * 100', legendFormat: '成功率 %', refId: 'A' },
          ], common.gridPos(4, 6, 0, 4), { unit: 'percent', thresholds: { steps: [{color:'green',value:99},{color:'yellow',value:95},{color:'red',value:90}] } }),
          common.panel('平均延迟', 'gauge', [
            { expr: 'histogram_quantile(0.99, sum(rate(notification_latency_seconds_bucket[5m])) by (le)) * 1000', legendFormat: 'P99 ms', refId: 'A' },
          ], common.gridPos(4, 6, 6, 4), { unit: 'ms' }),
          common.panel('队列积压', 'gauge', [
            { expr: 'sum(notification_queue_size)', legendFormat: '队列积压', refId: 'A' },
          ], common.gridPos(4, 6, 12, 4), { unit: 'short' }),
        ]),
        
        common.row('通知分布', [
          {
            title: '通知类型分布',
            type: 'piechart',
            gridPos: { h: 12, w: 12, x: 0, y: 8 },
            targets: [
              { expr: 'sum(rate(notification_sent_total[5m])) by (type)', legendFormat: '{{type}}', refId: 'A' },
            ],
            options: { displayMode: 'list' },
            fieldConfig: { defaults: { unit: 'short' } },
          },
          {
            title: '通知渠道分布',
            type: 'piechart',
            gridPos: { h: 12, w: 12, x: 12, y: 8 },
            targets: [
              { expr: 'sum(rate(notification_sent_total[5m])) by (channel)', legendFormat: '{{channel}}', refId: 'A' },
            ],
            options: { displayMode: 'list' },
            fieldConfig: { defaults: { unit: 'short' } },
          },
        ]),
        
        common.row('发送性能', [
          {
            title: '发送延迟分布',
            type: 'heatmap',
            gridPos: { h: 12, w: 12, x: 0, y: 16 },
            targets: [
              { expr: 'sum(rate(notification_latency_seconds_bucket[5m])) by (le)', legendFormat: '{{le}}', refId: 'A' },
            ],
            options: { calculate: true, xBucketBound: 'le' },
          },
          {
            title: '重试次数分布',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 12, y: 16 },
            targets: [
              { expr: 'sum(rate(notification_retry_total[5m])) by (attempt)', legendFormat: '重试 {{attempt}} 次', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'short' } },
          },
        ]),
        
        common.row('队列与重试', [
          {
            title: '队列积压',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 0, y: 16 },
            targets: [
              { expr: 'notification_queue_size', legendFormat: '队列大小', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'short', lines: true, fillOpacity: 10 } },
          },
          {
            title: '重试率',
            type: 'gauge',
            gridPos: { h: 4, w: 6, x: 12, y: 16 },
            targets: [
              { expr: 'sum(rate(notification_retry_total[5m])) / sum(rate(notification_sent_total[5m])) * 100', legendFormat: '重试率 %', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'percent', thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:5},{color:'red',value:10}] } } },
          },
          {
            title: '死信队列',
            type: 'stat',
            gridPos: { h: 4, w: 6, x: 6, y: 16 },
            targets: [
              { expr: 'notification_dead_letter_queue_size', legendFormat: '死信数', refId: 'A' },
            ],
            fieldConfig: { defaults: { thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:10},{color:'red',value:100}] } } },
          ),
          {
            title: '发送耗时 P99',
            type: 'gauge',
            gridPos: { h: 4, w: 6, x: 12, y: 16 },
            targets: [
              { expr: 'histogram_quantile(0.99, sum(rate(notification_send_duration_seconds_bucket[5m])) by (le)) * 1000', legendFormat: 'P99 ms', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'ms', thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:2000},{color:'red',value:5000}] } } },
          },
        ]),
      ],
    }
  }
}