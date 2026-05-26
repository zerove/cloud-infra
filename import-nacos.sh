#!/bin/bash
# 导入配置到 Nacos
NACOS_URL="http://nacos-service:8848/nacos"
CONFIG_DIR="deploy/config"

for file in $CONFIG_DIR/*.yml; do
    filename=$(basename "$file")
    data_id="${filename/-application.yml/.yaml}"
    echo ">>> 正在导入 $filename -> $data_id"

    content=$(cat "$file")

    curl -X POST "$NACOS_URL/v1/cs/configs" \
      -d "dataId=$data_id" \
      -d "group=DEFAULT_GROUP" \
      -d "content=$content"
    echo ""
done
