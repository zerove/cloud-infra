{
  // 通用变量和工具函数
  local common = {
    // 通用颜色
    colors: {
      primary: '#3263EB',
      success: '#10B981',
      warning: '#F59E0B',
      danger: '#EF4444',
      info: '#06B6D4',
    },

    // 通用面板配置
    panelDefaults: {
      gridPos: { h: 8, w: 12, x: 0, y: 0 },
      options: { legend: { displayMode: 'list', placement: 'bottom' } },
    },

    // 通用时间范围
    time: {
      from: 'now-1h',
      to: 'now',
    },

    // 通用刷新间隔
    refresh: '10s',

    // 生成面板网格位置
    gridPos(h, w, x, y): {
      h: h,
      w: w,
      x: x,
      y: y,
    },

    // 生成 Prometheus 查询
    promQL(query, legendFormat=''): {
      expr: query,
      legendFormat: legendFormat,
      refId: 'A',
    },

    // 生成面板
    panel(title, type, targets, gridPos, options={}, fieldConfig={}): {
      title: title,
      type: type,
      targets: targets,
      gridPos: gridPos,
      options: options,
      fieldConfig: {
        defaults: {
          unit: 'short',
          thresholds: { mode: 'absolute', steps: [{ color: 'green', value: null }] },
          ...fieldConfig,
        },
        overrides: [],
      },
    },

    // 行面板
    row(title, panels): {
      type: 'row',
      title: title,
      gridPos: { h: 1, w: 24, x: 0, y: 0 },
      collapsed: false,
      panels: panels,
    },
  },

  // 集群概览仪表盘
  clusterOverview: {
    title: 'Cloud-Infra 集群概览',
    tags: ['cloud-infra', 'overview'],
    timezone: 'Asia/Shanghai',
    refresh: '10s',
    time: { from: 'now-1h', to: 'now' },
    panels: [
      // 行: 集群状态
      common.row('集群整体状态', [
        common.panel('集群状态', 'stat', [
          common.promQL('count(up{namespace="cloud-infra"})', '存活服务数'),
          common.promQL('count(up{namespace="cloud-infra"} == 0)', '异常服务数'),
        ], common.gridPos(4, 6, 0, 0), {
          colorMode: 'value',
          graphMode: 'none',
          justifyContent: 'center',
        }, {
          thresholds: { steps: [{ color: 'green', value: null }, { color: 'red', value: 1 }] }
        }),
        common.panel('Pod 总数', 'stat', [
          common.promQL('count(kube_pod_info{namespace="cloud-infra"})', 'Pod总数'),
        ], common.gridPos(4, 6, 6, 0), {
          colorMode: 'value', graphMode: 'none', justifyContent: 'center'
        }),
        common.panel('CPU 使用率', 'gauge', [
          common.promQL('sum(rate(container_cpu_usage_seconds_total{namespace="cloud-infra"}[5m])) / sum(container_spec_cpu_quota/container_spec_cpu_period) * 100', 'CPU %'),
        ], common.gridPos(4, 6, 12, 0), { unit: 'percent' }),
        common.panel('内存使用率', 'gauge', [
          common.promQL('sum(container_memory_usage_bytes{namespace="cloud-infra"}) / sum(container_spec_memory_limit_bytes) * 100', '内存 %'),
        ], common.gridPos(4, 6, 18, 0), { unit: 'percent' }),
      ]),
      
      // 行: 服务健康度
      common.row('服务健康度', [
        common.panel('服务存活状态', 'table', [
          common.promQL('up{namespace="cloud-infra"}', '{{pod}}'),
        ], common.gridPos(8, 12, 0, 4), {
          showHeader: true,
        }),
        common.panel('服务重启次数', 'stat', [
          common.promQL('sum(increase(kube_pod_container_status_restarts_total{namespace="cloud-infra"}[1h]))', '重启总数'),
        ], common.gridPos(4, 4, 12, 4), {
          colorMode: 'value', graphMode: 'none',
          thresholds: { steps: [{color: 'green', value: null}, {color: 'yellow', value: 5}, {color: 'red', value: 10}] }
        }),
        common.panel('Pod 重启率', 'stat', [
          common.promQL('sum(rate(kube_pod_container_status_restarts_total{namespace="cloud-infra"}[1h]))', '每小时重启'),
        ], common.gridPos(4, 4, 16, 4), { unit: 'reqps' }),
        common.panel('OOM Killer 次数', 'stat', [
          common.promQL('sum(increase(container_oom_killed_total{namespace="cloud-infra"}[1h]))', 'OOM次数'),
        ], common.gridPos(4, 4, 20, 4), {
          thresholds: { steps: [{color: 'green', value: null}, {color: 'red', value: 1}] }
        }),
      ]),
      
      // 行: 资源使用趋势
      common.row('资源使用趋势', [
        common.panel('CPU 使用率趋势', 'timeseries', [
          common.promQL('sum(rate(container_cpu_usage_seconds_total{namespace="cloud-infra"}[5m])) by (pod)', '{{pod}}'),
        ], common.gridPos(8, 12, 0, 12), {
          lines: true, fillOpacity: 10,
          tooltip: { mode: 'single', sort: 'desc' },
        }),
        common.panel('内存使用趋势', 'timeseries', [
          common.promQL('container_memory_usage_bytes{namespace="cloud-infra"}', '{{pod}}'),
        ], common.gridPos(8, 12, 12, 12), {
          unit: 'bytes', lines: true, fillOpacity: 10,
        }),
      ]),
      
      // 行: 网络流量
      common.row('网络流量', [
        common.panel('入站流量', 'timeseries', [
          common.promQL('sum(rate(container_network_receive_bytes_total{namespace="cloud-infra"}[5m])) by (pod)', '{{pod}}'),
        ], common.gridPos(8, 12, 0, 20), { unit: 'Bps' }),
        common.panel('出站流量', 'timeseries', [
          common.promQL('sum(rate(container_network_transmit_bytes_total{namespace="cloud-infra"}[5m])) by (pod)', '{{pod}}'),
        ], common.gridPos(8, 12, 12, 12), { unit: 'Bps' }),
      ]),
    ],
  },

  // JVM 指标仪表盘
  jvmMetrics: {
    title: 'JVM 指标监控',
    tags: ['jvm', 'metrics'],
    timezone: 'Asia/Shanghai',
    refresh: '10s',
    time: { from: 'now-1h', to: 'now' },
    panels: [
      common.row('JVM 堆内存', [
        common.panel('堆内存使用率', 'gauge', [
          common.promQL('jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} * 100', '{{pod}}'),
        ], common.gridPos(4, 6, 0, 0), { unit: 'percent', thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:70},{color:'red',value:85}] }}),
        common.panel('堆内存使用量', 'timeseries', [
          common.promQL('jvm_memory_used_bytes{area="heap"}', '{{pod}}'),
          common.promQL('jvm_memory_max_bytes{area="heap"}', 'Max'),
        ], common.gridPos(8, 12, 0, 4), { unit: 'bytes' }),
        common.panel('非堆内存', 'timeseries', [
          common.promQL('jvm_memory_used_bytes{area="nonheap"}', '{{pod}}'),
        ], common.gridPos(8, 12, 12, 0), { unit: 'bytes' }),
        common.panel('直接内存', 'timeseries', [
          common.promQL('jvm_buffer_pool_used_bytes{pool="direct"}', '{{pool}}'),
        ], common.gridPos(4, 6, 18, 0), { unit: 'bytes' }),
      ]),
      common.row('GC 性能', [
        common.panel('GC 暂停时间', 'timeseries', [
          common.promQL('jvm_gc_pause_seconds_sum', '{{gc}}'),
        ], common.gridPos(8, 12, 0, 8), { unit: 's' }),
        common.panel('GC 频率', 'timeseries', [
          common.promQL('rate(jvm_gc_pause_seconds_count[5m])', '{{gc}}'),
        ], common.gridPos(8, 12, 12, 8), { unit: 'ops/s' }),
        common.panel('内存分配速率', 'timeseries', [
          common.promQL('rate(jvm_memory_allocated_bytes_total[5m])', '{{pod}}'),
        ], common.gridPos(8, 12, 0, 8), { unit: 'B/s' }),
        common.panel('GC 并发时间', 'timeseries', [
          common.promQL('jvm_gc_concurrent_phase_time_seconds', '{{gc}}'),
        ], common.gridPos(8, 12, 18, 8), { unit: 's' }),
      ]),
      common.row('线程与类加载', [
        common.panel('活跃线程数', 'timeseries', [
          common.promQL('jvm_threads_live', '{{pod}}'),
        ], common.gridPos(8, 12, 0, 16), { unit: 'short' }),
        common.panel('线程峰值', 'timeseries', [
          common.promQL('jvm_threads_peak', '{{pod}}'),
        ], common.gridPos(8, 12, 12, 16), { unit: 'short' }),
        common.panel('类加载数', 'timeseries', [
          common.promQL('jvm_classes_loaded', '{{pod}}'),
          common.promQL('jvm_classes_unloaded', '{{pod}}'),
        ], common.gridPos(8, 12, 18, 16), { unit: 'short' }),
        common.panel('线程状态', 'piechart', [
          common.promQL('jvm_threads_state{state="RUNNABLE"}', 'RUNNABLE'),
          common.promQL('jvm_threads_state{state="BLOCKED"}', 'BLOCKED'),
          common.promQL('jvm_threads_state{state="WAITING"}', 'WAITING'),
          common.promQL('jvm_threads_state{state="TIMED_WAITING"}', 'TIMED_WAITING'),
        ], common.gridPos(8, 12, 18, 16), {}),
      ]),
      common.row('GC 详情', [
        common.panel('各代 GC 次数', 'timeseries', [
          common.promQL('jvm_gc_pause_seconds_count', '{{gc}}'),
        ], common.gridPos(8, 12, 0, 24), { unit: 'ops' }),
        common.panel('各代 GC 时间', 'timeseries', [
          common.promQL('jvm_gc_pause_seconds_sum', '{{gc}}'),
        ], common.gridPos(8, 12, 12, 24), { unit: 's' }),
      ]),
    ],
  },

  // 业务指标仪表盘
  businessMetrics: {
    title: '业务指标监控',
    tags: ['business', 'metrics'],
    timezone: 'Asia/Shanghai',
    refresh: '30s',
    time: { from: 'now-1h', to: 'now' },
    panels: [
      common.row('用户活跃度', [
        common.panel('活跃用户数', 'stat', [
          common.promQL('business_active_users', '当前'),
        ], common.gridPos(4, 6, 0, 0), { colorMode: 'value' }),
        common.panel('新增用户数', 'stat', [
          common.promQL('increase(business_user_registered_total[1h])', '1小时'),
        ], common.gridPos(4, 6, 6, 0), { colorMode: 'value' }),
        common.panel('用户留存率', 'gauge', [
          common.promQL('business_user_retention_rate * 100', '留存率 %'),
        ], common.gridPos(4, 6, 12, 0), { unit: 'percent' }),
        common.panel('用户流失率', 'gauge', [
          common.promQL('business_user_churn_rate * 100', '流失率 %'),
        ], common.gridPos(4, 6, 18, 0), { unit: 'percent' }),
      ]),
      common.row('业务吞吐', [
        common.panel('请求总量', 'timeseries', [
          common.promQL('rate(business_requests_total[5m])', '{{service}}'),
        ], common.gridPos(8, 12, 0, 4), { unit: 'reqps' }),
        common.panel('成功率', 'gauge', [
          common.promQL('sum(rate(business_requests_total{status=~"2.."}[5m])) / sum(rate(business_requests_total[5m])) * 100', '成功率 %'),
        ], common.gridPos(4, 6, 12, 4), { unit: 'percent', thresholds: { steps: [{color:'green',value:99},{color:'yellow',value:95},{color:'red',value:90}] } }),
        common.panel('平均响应时间', 'gauge', [
          common.promQL('histogram_quantile(0.99, sum(rate(business_request_duration_seconds_bucket[5m])) by (le)) * 1000', 'P99 ms'),
        ], common.gridPos(4, 6, 18, 4), { unit: 'ms' }),
        common.panel('错误率', 'stat', [
          common.promQL('sum(rate(business_requests_total{status=~"5.."}[5m])) / sum(rate(business_requests_total[5m])) * 100', '错误率 %'),
        ], common.gridPos(4, 6, 24, 4), { 
          thresholds: { steps: [{color:'green',value:null},{color:'yellow',value:1},{color:'red',value:5}] }
        }),
      ]),
      common.row('业务分布', [
        common.panel('API 调用分布', 'piechart', [
          common.promQL('sum(rate(business_requests_total[5m])) by (api)', '{{api}}'),
        ], common.gridPos(12, 12, 0, 8), {}),
        common.panel('错误码分布', 'piechart', [
          common.promQL('sum(rate(business_requests_total{status=~"4..|5.."}[5m])) by (status)', '{{status}}'),
        ], common.gridPos(12, 12, 12, 8), {}),
      ]),
    ],
  },
}