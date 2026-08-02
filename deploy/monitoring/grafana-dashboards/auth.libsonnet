{
  local common = import 'common.libsonnet';

  {
    dashboard: {
      title: 'Cloud-Infra Auth Service 监控',
      uid: 'auth-metrics',
      tags: ['auth', 'security'],
      timezone: 'Asia/Shanghai',
      refresh: '10s',
      time: { from: 'now-1h', to: 'now' },
      panels: [
        common.row('Auth Service 概览', [
          common.panel('登录请求量', 'timeseries', [
            { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-auth",uri=~"/auth/.*"}[5m]))', legendFormat: '{{uri}}', refId: 'A' },
          ], common.gridPos(8, 12, 0, 0), { unit: 'reqps' }),
          common.panel('登录成功率', 'gauge', [
            { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-auth",uri=~"/auth/login",status=~"2.."}[5m])) / sum(rate(http_server_requests_seconds_count{application="cloud-infra-auth",uri=~"/auth/login"}[5m])) * 100', legendFormat: '登录成功率 %', refId: 'A' },
          ], common.gridPos(4, 6, 0, 4), { unit: 'percent', thresholds: { steps: [{color:'green',value:99},{color:'yellow',value:95},{color:'red',value:90}] } }),
          common.panel('登录延迟 P99', 'gauge', [
            { expr: 'histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{application="cloud-infra-auth",uri=~"/auth/login"}[5m])) by (le)) * 1000', legendFormat: 'P99 ms', refId: 'A' },
          ], common.gridPos(4, 6, 6, 4), { unit: 'ms' }),
          common.panel('JWT 刷新率', 'gauge', [
            { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-auth",uri=~"/auth/refresh"}[5m])) / sum(rate(http_server_requests_seconds_count{application="cloud-infra-auth",uri=~"/auth/login"}[5m])) * 100', legendFormat: '刷新率 %', refId: 'A' },
          ], common.gridPos(4, 6, 12, 4), { unit: 'percent' }),
        ]),
        
        common.row('认证安全', [
          {
            title: '失败登录尝试',
            type: 'stat',
            gridPos: { h: 4, w: 6, x: 0, y: 8 },
            targets: [
              { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-auth",uri=~"/auth/login",status=~"4.."}[5m]))', legendFormat: '失败/秒', refId: 'A' },
            ],
            fieldConfig: { defaults: { thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:10},{color:'red',value:50}] } } },
          },
          {
            title: '被锁定账户数',
            type: 'stat',
            gridPos: { h: 4, w: 6, x: 6, y: 8 },
            targets: [
              { expr: 'business_locked_accounts_total', legendFormat: '锁定数', refId: 'A' },
            ],
            fieldConfig: { defaults: { thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:5},{color:'red',value:20}] } } },
          },
          {
            title: 'JWT 刷新率',
            type: 'gauge',
            gridPos: { h: 4, w: 6, x: 12, y: 8 },
            targets: [
              { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-auth",uri=~"/auth/refresh"}[5m])) / sum(rate(http_server_requests_seconds_count{application="cloud-infra-auth",uri=~"/auth/login"}[5m])) * 100', legendFormat: '刷新率 %', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'percent', thresholds: { steps: [{color:'green',value:50},{color:'yellow',value:30},{color:'red',value:10}] } } },
          },
          {
            title: 'Token 刷新失败率',
            type: 'gauge',
            gridPos: { h: 4, w: 6, x: 18, y: 8 },
            targets: [
              { expr: 'sum(rate(http_server_requests_seconds_count{application="cloud-infra-auth",uri=~"/auth/refresh",status=~"4.."}[5m])) / sum(rate(http_server_requests_seconds_count{application="cloud-infra-auth",uri=~"/auth/refresh"}[5m])) * 100', legendFormat: '刷新失败率 %', refId: 'A' },
            ],
            fieldConfig: { defaults: { unit: 'percent', thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:5},{color:'red',value:20}] } } },
          },
        ]),
      ],
    }
  }
}