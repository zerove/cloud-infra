#!/bin/bash
# 等 system-module 在 Nacos 注册
NACOS=${NACOS_ADDR:-cloud-nacos:8848}
echo "[entrypoint] waiting for system-module to register..."
for i in $(seq 1 60); do
  IP=$(curl -s "http://$NACOS/nacos/v3/admin/ns/instance/list?serviceName=system-module&groupName=DEFAULT_GROUP&namespaceId=public" 2>/dev/null | \
    python3 -c "import sys,json; data=json.load(sys.stdin).get('data',[]); print(data[0].get('ip','') if data else '')" 2>/dev/null)
  if [ -n "$IP" ] && [ "$IP" != "198.18.0.30" ]; then
    echo "[entrypoint] system-module IP=$IP"
    # 注入 env (作为 JVM 系统属性, FeignHostConfig 读 SERVICE_HOST_MAP)
    export SERVICE_HOST_MAP="system-module:$IP"
    break
  fi
  sleep 2
done
exec java $JAVA_OPTS -jar app.jar
