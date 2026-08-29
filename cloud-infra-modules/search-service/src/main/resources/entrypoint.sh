#!/bin/bash
# 等 notification-service 在 Nacos 注册, 拉其 IP, 注入 SERVICE_HOST_MAP env
# 解决容器内 'notification-service' DNS 解析到 podman 内部错误 IP 198.18.0.30
# 让 FeignHostConfig (search-service 内的) 拿到实际 IP 替换 host header
set -e

NACOS=${NACOS_ADDR:-cloud-nacos:8848}
TARGET_SVC=${FEIGN_TARGET_SERVICE:-notification-service}

echo "[entrypoint] waiting for $TARGET_SVC to register..."
for i in $(seq 1 60); do
  IP=$(curl -s "http://$NACOS/nacos/v3/admin/ns/instance/list?serviceName=$TARGET_SVC&groupName=DEFAULT_GROUP&namespaceId=public" 2>/dev/null | \
    python3 -c "import sys,json; data=json.load(sys.stdin).get('data',[]); print(data[0].get('ip','') if data else '')" 2>/dev/null)
  if [ -n "$IP" ] && [ "$IP" != "198.18.0.30" ]; then
    echo "[entrypoint] $TARGET_SVC IP=$IP"
    # 注入 env (作为 JVM 系统属性, FeignHostConfig 读 SERVICE_HOST_MAP)
    export SERVICE_HOST_MAP="$TARGET_SVC:$IP"
    break
  fi
  sleep 2
done

exec java $JAVA_OPTS -jar app.jar
