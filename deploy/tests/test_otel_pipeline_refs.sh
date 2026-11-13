#!/usr/bin/env bash
# Negative control for check_otel_pipeline_refs.py
#
# The defect this guard covers is NOT hypothetical: it occurred for real on
# 2026-10-07 while removing otlp/jaeger (the definition was deleted, the
# pipeline kept listing it). So the first case below replays that exact
# occurrence against the production config.
#
# Each case uses its own sandbox directory (project lesson 8th note 1,
# case 5: sharing a mutable file between cases caused a real false green).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECK="$ROOT/deploy/tests/check_otel_pipeline_refs.py"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0

# 2026-10-13: 配置真源已收敛到 deploy/k8s/infra/values.yaml 的
# `otelCollector.config`(模板改为 toYaml 消费它), compose 侧那份随容器移除。
# 故夹具一律写到新路径, 且按真源的嵌套结构包裹。
mk() {
  local dir="$1"; shift
  mkdir -p "$dir/deploy/k8s/infra"
  {
    echo "otelCollector:"
    echo "  config:"
    sed 's/^/    /'
  } > "$dir/deploy/k8s/infra/values.yaml"
}

expect_rc() {
  local want="$1" desc="$2" dir="$3"
  local out rc
  out="$(cd "$dir" && python3 "$CHECK" 2>&1)"; rc=$?
  if [ "$rc" -eq "$want" ]; then
    printf '  PASS  %s (rc=%s)\n' "$desc" "$rc"; pass=$((pass + 1))
  else
    printf '  FAIL  %s (want rc=%s, got rc=%s)\n' "$desc" "$want" "$rc"
    echo "$out" | sed 's/^/        /'
    fail=$((fail + 1))
  fi
}

# ---- case 0: replay the real 2026-10-07 occurrence ------------------------
mk "$WORK/case0" <<'YAML'
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
processors:
  batch:
    timeout: 1s
exporters:
  debug:
    verbosity: basic
service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlp/jaeger, debug]
YAML
expect_rc 1 "case0 deleted-exporter still referenced (the real defect)" "$WORK/case0"

# ---- case 1: production config must be green right now --------------------
# 2026-10-13: 守护对象已从 compose 的 deploy/monitoring/otel-collector.yml
# **迁移**到 k8s 的 Helm 模板 (compose 侧 collector 随容器一并移除)。
# 本用例随之指向新位置 —— 否则它复制一个已不存在的文件, 恒定拿到 rc=2。
mkdir -p "$WORK/case1/deploy/k8s/infra"
cp "$ROOT/deploy/k8s/infra/values.yaml" "$WORK/case1/deploy/k8s/infra/values.yaml"
expect_rc 0 "case1 production otel config: green" "$WORK/case1"

# ---- case 2: dangling receiver -------------------------------------------
mk "$WORK/case2" <<'YAML'
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
processors:
  batch:
    timeout: 1s
exporters:
  debug:
    verbosity: basic
service:
  pipelines:
    traces:
      receivers: [otlp, ghost_receiver]
      processors: [batch]
      exporters: [debug]
YAML
expect_rc 1 "case2 dangling receiver: red" "$WORK/case2"

# ---- case 3: dangling processor ------------------------------------------
mk "$WORK/case3" <<'YAML'
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
processors:
  batch:
    timeout: 1s
exporters:
  debug:
    verbosity: basic
service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch, ghost_processor]
      exporters: [debug]
YAML
expect_rc 1 "case3 dangling processor: red" "$WORK/case3"

# ---- case 4: no exporters at all -> SCANNER_BROKEN, never green -----------
# This is the "scan zero targets and report green" trap: a collector with no
# exporters silently drops every span (project lesson 5th note 1).
mk "$WORK/case4" <<'YAML'
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
processors:
  batch:
    timeout: 1s
exporters: {}
service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [debug]
YAML
expect_rc 2 "case4 no exporters: scanner-broken, NOT green" "$WORK/case4"

# ---- case 5: no pipelines -> SCANNER_BROKEN ------------------------------
mk "$WORK/case5" <<'YAML'
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
processors:
  batch:
    timeout: 1s
exporters:
  debug:
    verbosity: basic
service:
  pipelines: {}
YAML
expect_rc 2 "case5 no pipelines: scanner-broken, NOT green" "$WORK/case5"

# ---- case 6: missing config file -> SCANNER_BROKEN ----------------------
mkdir -p "$WORK/case6/deploy/monitoring"
expect_rc 2 "case6 missing config: scanner-broken, NOT green" "$WORK/case6"

# ---- case 7: malformed YAML -> SCANNER_BROKEN --------------------------
mk "$WORK/case7" <<'YAML'
receivers:
  otlp:
   - [unclosed
YAML
expect_rc 2 "case7 malformed YAML: scanner-broken, NOT green" "$WORK/case7"

echo
echo "negative-control suite: $pass passed, $fail failed"
[ "$fail" -eq 0 ]