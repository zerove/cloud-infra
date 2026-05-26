#!/bin/bash
echo ">>> 正在开启端口转发: localhost:9000 -> gateway-service:9000"
kubectl port-forward --address 0.0.0.0 svc/gateway-service 9000:9000 > /dev/null 2>&1 &
echo  > .port-forward.pid
echo ">>> 端口转发已在后台运行 (PID: )"
