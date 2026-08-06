#!/bin/bash
# 扫描 YAML 重复顶层键
cd /home/zeron/dev/cloud-infra
for f in $(find deploy -name "*.yaml" -o -name "*.yml" | sort); do
  # 使用 python 检查重复键
  python3 - "$f" <<'EOF'
import sys, yaml
from collections import defaultdict
path = sys.argv[1]
try:
    with open(path) as fh:
        data = yaml.safe_load(fh)
    if data is None:
        sys.exit(0)
except Exception as e:
    print(f"SKIP {path}: {e}")
    sys.exit(0)
# 只检查顶层 key 重复（yaml.safe_load 会保留最后一个，但重复检测需要手动）
try:
    with open(path) as fh:
        lines = fh.readlines()
except Exception:
    sys.exit(0)
seen = {}
in_multi = False
multi_key = None
for i, line in enumerate(lines, 1):
    stripped = line.rstrip("\n")
    if "---" in stripped or "..." in stripped:
        in_multi = not in_multi
        continue
    if not stripped.strip() or stripped.strip().startswith("#"):
        continue
    indent = len(stripped) - len(stripped.lstrip(" "))
    if indent == 0 and ":" in stripped:
        key = stripped.split(":", 1)[0].strip()
        if key in seen:
            print(f"DUP {path}: line {i} key '{key}' first at line {seen[key]}")
        seen[key] = i
EOF
done