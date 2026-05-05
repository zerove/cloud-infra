#!/bin/bash
# 获取脚本所在目录
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
OUTPUT="$DIR/services.json"

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
