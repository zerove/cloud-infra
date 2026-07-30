#!/bin/bash
echo "Starting port forwarding for Cloud-Infra services..."
kubectl port-forward --address 0.0.0.0 service/gateway-service 9000:9000 &
kubectl port-forward --address 0.0.0.0 service/elasticsearch 9200:9200 &
kubectl port-forward --address 0.0.0.0 service/rabbitmq 15672:15672 &
echo "Services are now accessible via http://localhost:9000 (Gateway), :9200 (ES), :15672 (RabbitMQ)"
