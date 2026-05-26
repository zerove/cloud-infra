#!/bin/bash
reg_name='kind-registry'
reg_port='5001'
if [ "$(podman inspect -f '{{.State.Running}}' "${reg_name}" 2>/dev/null || true)" != 'true' ]; then
  podman run -d --restart=always -p "127.0.0.1:${reg_port}:5000" --network bridge --name "${reg_name}" registry:2
fi

kind create cluster --config scripts/kind-config.yaml
# Podman's default network is often 'podman', but Kind uses its own. 
# We need to bridge the registry to the kind network if possible, 
# or ensure the cluster can reach the host.
# For now, start the cluster.
