#!/bin/bash
# Nacos Config Sync Script
NACOS_ADDR="http://localhost:8848"
CONFIG_DIR="./configs"

echo "Syncing configs to Nacos..."

for file in $CONFIG_DIR/*.yaml; do
    filename=$(basename "$file")
    data_id="${filename}"
    content=$(cat "$file")
    
    echo "Publishing $data_id to Nacos..."
    curl -X POST "$NACOS_ADDR/nacos/v1/cs/configs" \
      -d "dataId=$data_id" \
      -d "group=DEFAULT_GROUP" \
      -d "content=$content" \
      -d "type=yaml"
done

echo "Sync completed."
