{
  local gateway = import 'gateway.libsonnet';
  local system = import 'system.libsonnet';
  local auth = import 'auth.libsonnet';
  local file = import 'file.libsonnet';
  local notification = import 'notification.libsonnet';
  local search = import 'search.libsonnet';
  local infra = import 'infra.libsonnet';
  local common = import 'common.libsonnet';

  {
    'gateway-dashboard.json': gateway.dashboard,
    'system-dashboard.json': system.dashboard,
    'auth-dashboard.json': auth.dashboard,
    'file-dashboard.json': file.dashboard,
    'notification-dashboard.json': notification.dashboard,
    'search-dashboard.json': search.dashboard,
    'infra-dashboard.json': infra.dashboard,
    'cluster-overview.json': common.clusterOverview,
    'jvm-metrics.json': common.jvmMetrics,
    'business-metrics.json': common.businessMetrics,
  }
}