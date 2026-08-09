#!/bin/bash
# Cloud-Infra Sentinel 规则 JSON 校验
# 校验 deploy/config/cloud-infra-gateway-sentinel-*.json 的 schema 合法性
# 用法: ./validate-sentinel.sh [文件...]  (默认全部)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG_DIR="${1:+$1}"
CONFIG_DIR="${CONFIG_DIR:-$SCRIPT_DIR/config}"

FAIL=0

validate_flow() {
  python3 - "$1" << 'PYEOF'
import json, sys
path = sys.argv[1]
with open(path) as f:
    rules = json.load(f)
if not isinstance(rules, list):
    raise SystemExit(f"{path}: 顶层应为 JSON 数组")
for i, r in enumerate(rules):
    if "resource" not in r or not r["resource"]:
        raise SystemExit(f"{path}[{i}]: 缺少 resource")
    if "count" not in r or not isinstance(r["count"], (int, float)):
        raise SystemExit(f"{path}[{i}]: 缺少有效 count 阈值")
    if "grade" not in r or r["grade"] not in (0, 1):
        raise SystemExit(f"{path}[{i}]: grade 必须为 0(线程)/1(QPS)")
print(f"✓ {path}: {len(rules)} 条 flow 规则合法")
PYEOF
}

validate_degrade() {
  python3 - "$1" << 'PYEOF'
import json, sys
path = sys.argv[1]
with open(path) as f:
    rules = json.load(f)
if not isinstance(rules, list):
    raise SystemExit(f"{path}: 顶层应为 JSON 数组")
for i, r in enumerate(rules):
    if "resource" not in r or not r["resource"]:
        raise SystemExit(f"{path}[{i}]: 缺少 resource")
    if "count" not in r or not isinstance(r["count"], (int, float)):
        raise SystemExit(f"{path}[{i}]: 缺少有效 count")
    if r.get("grade") not in (0, 1, 2):
        raise SystemExit(f"{path}[{i}]: grade 必须为 0/1/2")
print(f" degrade: {path}: {len(rules)} 条熔断规则合法")
PYEOF
}

validate_param_flow() {
  python3 - "$1" << 'PYEOF'
import json, sys
path = sys.argv[1]
with open(path) as f:
    rules = json.load(f)
if not isinstance(rules, list):
    raise SystemExit(f"{path}: 顶层应为 JSON 数组")
for i, r in enumerate(rules):
    if "resource" not in r or not r["resource"]:
        raise SystemExit(f"{path}[{i}]: 缺少 resource")
    if "count" not in r or not isinstance(r["count"], (int, float)):
        raise SystemExit(f"{path}[{i}]: 缺少有效 count")
print(f" param-flow {path}: {len(rules)} 条热点规则合法")
PYEOF
}

validate_system() {
  python3 - "$1" << 'PYEOF'
import json, sys
path = sys.argv[1]
with open(path) as f:
    rules = json.load(f)
if not isinstance(rules, list):
    raise SystemExit(f"{path}: 顶层应为 JSON 数组")
# system 规则允许空 (系统保护默认关闭)
print(f"system {path}: {len(rules)} 条系统规则合法")
PYEOF
}

validate_authority() {
  python3 - "$1" << 'PYEOF'
import json, sys
path = sys.argv[1]
with open(path) as f:
    rules = json.load(f)
if not isinstance(rules, list):
    raise SystemExit(f"{path}: 顶层应为 JSON 数组")
for i, r in enumerate(rules):
    if "resource" not in r or not r["resource"]:
        raise SystemExit(f"{path}[{i}]: 缺少 resource")
    if "limitApp" not in r or not r["limitApp"]:
        raise SystemExit(f"{path}[{i}]: 缺少 limitApp")
print(f"authority {path}: {len(rules)} 条授权规则合法")
PYEOF
}

for f in "$CONFIG_DIR"/cloud-infra-gateway-sentinel-*.json; do
  [ -e "$f" ] || continue
  case "$(basename "$f")" in
    *param-flow.json) validate_param_flow "$f" ;;
    *degrade.json) validate_degrade "$f" ;;
    *authority.json) validate_authority "$f" ;;
    *system.json) validate_system "$f" ;;
    *flow.json) validate_flow "$f" ;;
  esac || FAIL=1
done

if [ "$FAIL" -eq 0 ]; then
  echo "✓ 全部 Sentinel 规则校验通过"
else
  echo "✗ 存在校验失败"
  exit 1
fi