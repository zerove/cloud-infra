#!/bin/sh
# 等 notification-service 在 Nacos 注册, 拉其 IP, 注入 SERVICE_HOST_MAP env
# 解决容器内 'notification-service' DNS 解析到 podman 内部错误 IP 198.18.0.30
# 让 FeignHostConfig (search-service 内的) 拿到实际 IP 替换 host header
# 注: alpine 基础镜像无 bash/python3, 用 POSIX sh + grep/sed 解析 Nacos JSON
set -e

NACOS=${NACOS_ADDR:-cloud-nacos:8848}
TARGET_SVC=${FEIGN_TARGET_SERVICE:-notification-service}

echo "[entrypoint] waiting for $TARGET_SVC to register..."
i=0
while [ $i -lt 60 ]; do
  # Nacos 响应: {"code":0,"data":[{"ip":"10.89.0.23",...},...]}
  # grep -o 提取第一个 "ip":"X.X.X.X"
  IP=$(curl -s "http://$NACOS/nacos/v3/admin/ns/instance/list?serviceName=$TARGET_SVC&groupName=DEFAULT_GROUP&namespaceId=public" 2>/dev/null \
    | grep -o '"ip":"[^"]*"' | head -1 | cut -d'"' -f4)
  if [ -n "$IP" ] && [ "$IP" != "198.18.0.30" ]; then
    echo "[entrypoint] $TARGET_SVC IP=$IP"
    export SERVICE_HOST_MAP="$TARGET_SVC:$IP"
    break
  fi
  i=$((i+1))
  sleep 2
done

exec java $JAVA_OPTS -jar app.jar
