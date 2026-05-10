#!/bin/bash
# Nacos Config Sync Script
NACOS_ADDR="http://localhost:8848"
CONFIG_DIR="./config"

echo "Syncing configs to Nacos..."

for file in $CONFIG_DIR/*; do
    filename=$(basename "$file")
    extension="${filename##*.}"
    data_id="${filename}"
    content=$(cat "$file")
    
    # Determine type (json or yaml)
    type="yaml"
    if [ "$extension" == "json" ]; then
        type="json"
    fi
    
    echo "Publishing $data_id (type: $type) to Nacos..."
    curl -X POST "$NACOS_ADDR/nacos/v1/cs/configs" \
      -d "dataId=$data_id" \
      -d "group=DEFAULT_GROUP" \
      -d "content=$content" \
      -d "type=$type"
done

echo "Sync completed."
