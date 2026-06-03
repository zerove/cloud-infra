#!/bin/bash
modules=("cloud-infra-gateway" "cloud-infra-modules/file-service" "cloud-infra-modules/notification-service" "cloud-infra-modules/search-service" "cloud-infra-modules/system-module")

for mod in "${modules[@]}"; do
  path="/home/zeron/dev/cloud-infra/$mod/src/main/resources"
  if [ -f "$path/application.yml" ]; then
    echo "Processing $mod..."
    # 简单提取应用名
    appName=$(grep -E "name:" "$path/application.yml" | head -n 1 | awk '{print $2}')
    if [ -z "$appName" ]; then appName="cloud-${mod##*/}"; fi
    
    cat > "$path/bootstrap.yml" <<CONF
spring:
  application:
    name: $appName
  cloud:
    nacos:
      discovery:
        server-addr: ${SPRING_CLOUD_NACOS_SERVER_ADDR:nacos:8848}
      config:
        server-addr: ${SPRING_CLOUD_NACOS_SERVER_ADDR:nacos:8848}
        file-extension: yaml
        namespace: public
        group: DEFAULT_GROUP
        extension-configs:
          - data-id: $appName.yaml
            group: DEFAULT_GROUP
            refresh: true
CONF
    rm "$path/application.yml"
  fi
done
