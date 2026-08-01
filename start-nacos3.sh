#!/bin/bash
# 启动 Nacos 3.x 测试服务器
podman rm -f test-nacos3 2>/dev/null
TOKEN=$(echo -n "cloud-infra-secret-key-2026-very-long-secret-key-for-jwt-token-generation" | base64 -w0)
podman run -d --name test-nacos3 \
  -p 8848:8848 -p 9848:9848 -p 9849:9849 \
  -e MODE=standalone \
  -e NACOS_AUTH_TOKEN="$TOKEN" \
  -e NACOS_AUTH_IDENTITY_KEY=serverIdentity \
  -e NACOS_AUTH_IDENTITY_VALUE=security \
  docker.io/nacos/nacos-server:v3.0.2
echo "Nacos 3.x 已启动，等待就绪..."
