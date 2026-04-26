#!/bin/bash
# 自动生成 Prometheus 服务发现配置文件

OUTPUT="/home/zeron/dev/cloud-infra/deploy/data/prometheus/services.json"

# 获取带有 monitoring 标签的容器
containers=$(podman ps --filter "label=monitoring=true" --format "{{.Names}}:{{.Ports}}")

echo "[" > $OUTPUT
first=true
for entry in $containers; do
    name=${entry%%:*}
    port=${entry#*:}
    port=${port%%/*}
    
    if [ "$first" = true ]; then
        first=false
    else
        echo "," >> $OUTPUT
    fi
    
    cat <<EOF >> $OUTPUT
{
    "targets": ["$name:$port"],
    "labels": {
        "job": "microservices",
        "instance": "$name"
    }
}
EOF
done
echo "]" >> $OUTPUT
