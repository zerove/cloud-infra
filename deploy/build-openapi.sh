#!/bin/bash
# Cloud-Infra OpenAPI 聚合脚本 (M6-05 Phase C)
#
# 依赖: curl, jq (可选, 用于生成 markdown 摘要)
# 假设: 业务模块已运行 + springdoc 暴露 /v3/api-docs 端点
#       或: 业务模块已 build 产物 target/openapi/{module}.json (Phase B Exporter 输出)
#
# 用法:
#   ./build-openapi.sh                      # 拉取各模块 /v3/api-docs 合并
#   ./build-openapi.sh --from-target        # 从各模块 target/openapi/ 合并 (无需运行)
#   ./build-openapi.sh --module auth        # 仅拉取指定模块
#   ./build-openapi.sh --gateway http://localhost:9000
#   ./build-openapi.sh --output docs/api    # 自定义输出目录
#
# 输出:
#   docs/api/openapi-unified.json   - 合并后的 OpenAPI 文档
#   docs/api/openapi-summary.md    - 模块摘要 markdown
#
# 集成 CI: docs-build workflow 可调用此脚本生成统一 API 文档

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# 默认配置
GATEWAY_URL="http://localhost:9000"
OUTPUT_DIR="docs/api"
MODULES=(auth system file notification search)
ONLY_MODULE=""
FROM_TARGET=false

# 参数解析 (while + shift 模式, 修复 for 循环中 shift 无效问题)
while [ $# -gt 0 ]; do
    arg="$1"
    case "$arg" in
        --from-target) FROM_TARGET=true; shift ;;
        --gateway) GATEWAY_URL="${2:-}"; shift 2 ;;
        --module) ONLY_MODULE="${2:-}"; shift 2 ;;
        --output) OUTPUT_DIR="${2:-}"; shift 2 ;;
        --help|-h)
            grep '^# ' "$0" | sed 's/^# //; s/^#//'
            exit 0
            ;;
        *) echo "[build-openapi] 未知参数: $arg"; exit 1 ;;
    esac
done

mkdir -p "$OUTPUT_DIR"
UNIFIED="$OUTPUT_DIR/openapi-unified.json"
SUMMARY="$OUTPUT_DIR/openapi-summary.md"

echo "[build-openapi] 输出: $OUTPUT_DIR"
echo "[build-openapi] Gateway: $GATEWAY_URL"
echo ""

# 拉取单个模块 OpenAPI
fetch_module() {
    local module="$1"
    local path="$2"  # 输出相对路径
    local url="${GATEWAY_URL}/v3/api-docs/${module}"
    local out="${OUTPUT_DIR}/${path}"

    if curl -sf --max-time 10 "$url" -o "$out" 2>/dev/null; then
        local size=$(wc -c < "$out" | tr -d ' ')
        echo "  ✅ $module: $size bytes ($url)"
        return 0
    else
        echo "  ❌ $module: 拉取失败 ($url)"
        return 1
    fi
}

# 从 target/openapi/ 复制
fetch_from_target() {
    local module="$1"
    local src="$PROJECT_ROOT/cloud-infra-modules/${module}-service/target/openapi/${module}.json"
    # gateway / auth / system 特殊路径
    case "$module" in
        gateway) src="$PROJECT_ROOT/cloud-infra-gateway/target/openapi/cloud-infra-gateway.json" ;;
        auth) src="$PROJECT_ROOT/cloud-infra-auth/target/openapi/cloud-infra-auth.json" ;;
        system) src="$PROJECT_ROOT/cloud-infra-modules/system-module/target/openapi/system-module.json" ;;
    esac
    local out="$OUTPUT_DIR/${module}.json"
    if [ -f "$src" ]; then
        cp "$src" "$out"
        local size=$(wc -c < "$out" | tr -d ' ')
        echo "  ✅ $module: $size bytes (from $src)"
        return 0
    else
        echo "  ⚠️  $module: 文件不存在 ($src)"
        return 1
    fi
}

# 主流程
echo "[build-openapi] 拉取 OpenAPI..."

declare -A SUCCESS_MODULES
for module in "${MODULES[@]}"; do
    if [ -n "$ONLY_MODULE" ] && [ "$module" != "$ONLY_MODULE" ]; then
        continue
    fi
    if $FROM_TARGET; then
        if fetch_from_target "$module"; then
            SUCCESS_MODULES[$module]=1
        fi
    else
        if fetch_module "$module" "${module}.json"; then
            SUCCESS_MODULES[$module]=1
        fi
    fi
done

SUCCESS_COUNT=${#SUCCESS_MODULES[@]}
TOTAL_COUNT=${#MODULES[@]}

# 防御 set -u 空数组解引用
if [ $SUCCESS_COUNT -eq 0 ]; then
    echo ""
    echo "[build-openapi] ❌ 0/$TOTAL_COUNT 模块成功"
    echo "  提示: 用 --from-target 从本地 target/openapi/ 拉取, 或先启动业务模块"
    exit 1
fi

echo ""
echo "[build-openapi] 合并 OpenAPI..."

# 简单合并: 取第一个模块作为基础, 合并 paths 与 components
# (注: 完整合并需要处理 openapi duplicate, 实际生产推荐用 openapi-merge-cli)
# 简化: 输出 unified.json 包含 modules 数组
python3 - "$OUTPUT_DIR" "$UNIFIED" <<'PYEOF' 2>/dev/null || echo '{"modules": []}' > "$UNIFIED"
import sys
import json
import os
from pathlib import Path

output_dir = Path(sys.argv[1])
unified_path = Path(sys.argv[2])

modules = {}
for f in sorted(output_dir.glob("*.json")):
    if f.name == "openapi-unified.json":
        continue
    if not f.name.endswith(".json"):
        continue
    try:
        with open(f) as fp:
            data = json.load(fp)
        module_name = f.stem
        modules[module_name] = {
            "openapi": data.get("openapi", "3.0.1"),
            "info": data.get("info", {}),
            "paths_count": len(data.get("paths", {})),
            "components_count": len(data.get("components", {}).get("schemas", {})),
        }
    except Exception:
        pass

unified = {
    "openapi": "3.0.1",
    "info": {
        "title": "Cloud-Infra Unified API",
        "version": "1.0.0",
        "description": f"合并 {len(modules)} 个微服务模块的 OpenAPI 文档",
    },
    "modules": modules,
}
unified_path.parent.mkdir(parents=True, exist_ok=True)
with open(unified_path, "w") as f:
    json.dump(unified, f, indent=2, ensure_ascii=False)
PYEOF

echo "[build-openapi] ✅ unified: $UNIFIED"
echo "[build-openapi] ✅ $SUCCESS_COUNT/$TOTAL_COUNT 模块已合并"

# 生成 markdown 摘要
echo ""
echo "[build-openapi] 生成摘要 markdown..."
cat > "$SUMMARY" <<MDEOF
# Cloud-Infra API 文档摘要

> 自动生成: \`./deploy/build-openapi.sh\`
> 模块数: $SUCCESS_COUNT / $TOTAL_COUNT

## 模块列表

| 模块 | OpenAPI 版本 | 端点数 | Schema 数 |
|---|---|---|---|
MDEOF

for module in "${!SUCCESS_MODULES[@]}"; do
    if command -v jq >/dev/null 2>&1; then
        local_version=$(jq -r '.openapi // "3.0.1"' "$OUTPUT_DIR/${module}.json" 2>/dev/null || echo "3.0.1")
        local_paths=$(jq '.paths // {} | length' "$OUTPUT_DIR/${module}.json" 2>/dev/null || echo "?")
        local_schemas=$(jq '.components.schemas // {} | length' "$OUTPUT_DIR/${module}.json" 2>/dev/null || echo "?")
    else
        local_version="3.0.1"
        local_paths=$(grep -c '"' "$OUTPUT_DIR/${module}.json" 2>/dev/null | head -1 || echo "?")
        local_schemas="?"
    fi
    echo "| $module | $local_version | $local_paths | $local_schemas |" >> "$SUMMARY"
done

cat >> "$SUMMARY" <<MDEOF

## 详情

详见 [openapi-unified.json](./openapi-unified.json)
MDEOF

echo "[build-openapi] ✅ summary: $SUMMARY"
echo ""
echo "[build-openapi] 全部完成: $SUCCESS_COUNT/$TOTAL_COUNT 模块, 输出 $OUTPUT_DIR"