{
  local common = import 'common.libsonnet';

  {
    dashboard: {
      title: 'Cloud-Infra File Service 监控',
      uid: 'file-metrics',
      tags: ['file', 'storage'],
      timezone: 'Asia/Shanghai',
      refresh: '10s',
      time: { from: 'now-1h', to: 'now' },
      panels: [
        common.row('File Service 概览', [
          common.panel('上传请求量', 'timeseries', [
            { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-file",uri=~"/file/upload"}[5m]))', legendFormat: '上传', refId: 'A' },
            { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-file",uri=~"/file/download"}[5m]))', legendFormat: '下载', refId: 'B' },
          ], common.gridPos(8, 12, 0, 0), { unit: 'reqps' }),
          common.panel('上传成功率', 'gauge', [
            { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-file",uri=~"/file/upload",status=~"2.."}[5m])) / sum(rate(http_server_requests_seconds_count{application="cloud-infra-file",uri=~"/file/upload"}[5m])) * 100', legendFormat: '上传成功率 %', refId: 'A' },
          ], common.gridPos(4, 6, 0, 4), { unit: 'percent', thresholds: { steps: [{color:'green',value:99},{color:'yellow',value:95},{color:'red',value:90}] } }),
          common.panel('上传延迟 P99', 'gauge', [
            { expr: 'histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-file",uri=~"/file/upload"}[5m])) by (le)) * 1000', legendFormat: 'P99 ms', refId: 'A' },
          ], common.gridPos(4, 6, 6, 4), { unit: 'ms' }),
          common.panel('存储使用量', 'gauge', [
            { expr: 'minio_bucket_usage_bytes{bucket="files"}', legendFormat: '存储字节', refId: 'A' },
          ], common.gridPos(4, 6, 12, 4), { unit: 'bytes' }),
        ]),
        
        common.row('文件分布', [
          {
            title: '文件类型分布',
            type: 'piechart',
            gridPos: { h: 12, w: 12, x: 0, y: 8 },
            targets: [
              { expr: 'sum(rate(file_uploads_total[5m])) by (content_type)', legendFormat: '{{content_type}}', refId: 'A' },
            ],
            options: { displayMode: 'list' },
            fieldConfig: { defaults: { unit: 'short' } },
          },
          {
            title: '文件大小分布',
            type: 'heatmap',
            gridPos: { h: 12, w: 12, x: 12, y: 8 },
            targets: [
              { expr: 'sum(rate(file_size_bytes_bucket[5m])) by (le)', legendFormat: '{{le}}', refId: 'A' },
            ],
            options: { calculate: true, xBucketBound: 'le' },
          },
        ]),
        
        common.row('存储与性能', [
          {
            title: '存储使用量趋势',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 0, y: 16 },
            targets: [
              { expr: 'minio_bucket_usage_bytes{bucket="files"}', legendFormat: '总存储', refId: 'A' },
              { expr: 'minio_bucket_objects_total{bucket="files"}', legendFormat: '对象数', refId: 'B' },
            ],
            fieldConfig: { defaults: { unit: 'bytes', lines: true, fillOpacity: 10 } },
          },
          {
            title: 'MinIO 请求延迟',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 12, y: 16 },
            targets: [
              { expr: 'histogram_quantile(0.99, sum(rate(minio_s3_request_duration_seconds_bucket{bucket="files"}[5m])) by (le)) * 1000', legendFormat: 'P99 ms', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'ms', lines: true, fillOpacity: 10 } },
          },
        ]),
        
        common.row('上传/下载性能', [
          {
            title: '上传吞吐量',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 0, y: 16 },
            targets: [
              { expr: 'rate(minio_s3_requests_total{bucket="files",operation="PUT"}[5m])', legendFormat: '上传 QPS', refId: 'A' },
              { expr: 'rate(minio_s3_bytes_sent_total{bucket="files"}[5m])', legendFormat: '上传带宽', refId: 'B' },
            ],
            fieldConfig: { defaults: { unit: 'Bps', lines: true, fillOpacity: 10 } },
          },
          {
            title: '下载吞吐量',
            type: 'timeseries',
            gridPos: { h: 8, w: 12, x: 12, y: 16 },
            targets: [
              { expr: 'rate(minio_s3_requests_total{bucket="files",operation="GET"}[5m])', legendFormat: '下载 QPS', refId: 'A' },
              { expr: 'rate(minio_s3_bytes_received_total{bucket="files"}[5m])', legendFormat: '下载带宽', refId: 'B' },
            ],
            fieldConfig: { defaults: { unit: 'Bps', lines: true, fillOpacity: 10 } },
          },
        ]),
      ],
    }
  }
}