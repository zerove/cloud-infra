# Native Build and Kind Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a high-performance Native image build pipeline using host-side GraalVM JDK 25 and deploy to a Kind cluster with a local registry, maintaining connectivity to external middleware.

**Architecture:** Host-based compilation using GraalVM for speed, lightweight Docker packaging (Debian-slim), and Kubernetes ExternalName Services for transparent middleware access.

**Tech Stack:** GraalVM JDK 25, Maven, Docker, Kind, Kubernetes.

---

### Task 1: Initialize Kind Cluster and Local Registry

**Files:**
- Create: `scripts/setup-kind.sh`
- Create: `scripts/kind-config.yaml`

- [ ] **Step 1: Create Kind configuration with local registry support**

```yaml
# scripts/kind-config.yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
containerdConfigPatches:
- |-
  [plugins."io.containerd.grpc.v1.cri".registry.mirrors."localhost:5001"]
    endpoint = ["http://kind-registry:5000"]
nodes:
- role: control-plane
  extraPortMappings:
  - containerPort: 9000
    hostPort: 9000
    protocol: TCP
```

- [ ] **Step 2: Create setup script for registry and cluster**

```bash
# scripts/setup-kind.sh
#!/bin/bash
reg_name='kind-registry'
reg_port='5001'
if [ "$(docker inspect -f '{{.State.Running}}' "${reg_name}" 2>/dev/null || true)" != 'true' ]; then
  docker run -d --restart=always -p "127.0.0.1:${reg_port}:5000" --network bridge --name "${reg_name}" registry:2
fi

kind create cluster --config scripts/kind-config.yaml
docker network connect "kind" "${reg_name}" || true

cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-registry-hosting
  namespace: kube-public
data:
  localRegistryHosting.v1: |
    host: "localhost:${reg_port}"
    help: "https://kind.sigs.k8s.io/docs/user/local-registry/"
EOF
```

- [ ] **Step 3: Run setup script and verify**

Run: `chmod +x scripts/setup-kind.sh && ./scripts/setup-kind.sh`
Expected: Kind cluster "kind" is created, and `kubectl cluster-info` works.

- [ ] **Step 4: Commit**

```bash
git add scripts/kind-config.yaml scripts/setup-kind.sh
git commit -m "chore: add kind and local registry setup scripts"
```

---

### Task 2: Configure Kubernetes Networking for External Middleware

**Files:**
- Create: `manifests/infrastructure/external-middleware.yaml`

- [ ] **Step 1: Define ExternalName services for middleware**

```yaml
# manifests/infrastructure/external-middleware.yaml
apiVersion: v1
kind: Service
metadata:
  name: nacos-service
spec:
  type: ExternalName
  externalName: host.docker.internal
---
apiVersion: v1
kind: Service
metadata:
  name: postgres-service
spec:
  type: ExternalName
  externalName: host.docker.internal
---
apiVersion: v1
kind: Service
metadata:
  name: redis-service
spec:
  type: ExternalName
  externalName: host.docker.internal
```

- [ ] **Step 2: Apply services and verify DNS**

Run: `kubectl apply -f manifests/infrastructure/external-middleware.yaml`
Run: `kubectl run dns-test --image=busybox:1.28 --restart=Never -- sleep 3600` (Wait for it to start)
Run: `kubectl exec dns-test -- nslookup nacos-service`
Expected: Resolves to an IP or the external name.

- [ ] **Step 3: Commit**

```bash
git add manifests/infrastructure/external-middleware.yaml
git commit -m "feat: add external middleware connectivity via ExternalName services"
```

---

### Task 3: Standardize Native Build Output in POM

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Ensure `native-maven-plugin` uses a standard output name**

In `pom.xml` under the `native` profile for `native-maven-plugin` configuration:

```xml
<configuration>
    <imageName>${project.artifactId}-binary</imageName>
    <!-- ... other config ... -->
</configuration>
```

- [ ] **Step 2: Test native compilation on one module (e.g., cloud-infra-auth)**

Run: `mvn clean package -Pnative -pl cloud-infra-auth -am -DskipTests`
Expected: `cloud-infra-auth/target/cloud-infra-auth-binary` exists.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: standardize native image output name in pom.xml"
```

---

### Task 4: Create Native Build and Push Script

**Files:**
- Create: `scripts/build-native-image.sh`
- Create: `Dockerfile.native-pack`

- [ ] **Step 1: Create lightweight Dockerfile for packing**

```dockerfile
# Dockerfile.native-pack
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ARG BINARY_PATH
COPY ${BINARY_PATH} /app/app
ENTRYPOINT ["/app/app"]
```

- [ ] **Step 2: Create build and push script**

```bash
# scripts/build-native-image.sh
#!/bin/bash
MODULE=$1
if [ -z "$MODULE" ]; then echo "Usage: $0 [module-name]"; exit 1; fi

echo ">>> Building native binary for $MODULE..."
mvn clean package -Pnative -pl $MODULE -am -DskipTests

BINARY="target/${MODULE}-binary"
IMAGE="localhost:5001/cloud-infra/${MODULE}:latest"

echo ">>> Building and pushing docker image $IMAGE..."
docker build -f Dockerfile.native-pack --build-arg BINARY_PATH="${MODULE}/${BINARY}" -t "${IMAGE}" .
docker push "${IMAGE}"
```

- [ ] **Step 3: Test full build and push for auth module**

Run: `chmod +x scripts/build-native-image.sh && ./scripts/build-native-image.sh cloud-infra-auth`
Expected: Image is pushed to `localhost:5001/cloud-infra/cloud-infra-auth:latest`.

- [ ] **Step 4: Commit**

```bash
git add Dockerfile.native-pack scripts/build-native-image.sh
git commit -m "build: add native build and push automation scripts"
```

---

### Task 5: Deploy and Verify in Kind

**Files:**
- Modify: `manifests/base/auth-deployment.yaml` (example)

- [ ] **Step 1: Update deployment to use local registry image**

```yaml
# Update image in manifests/base/auth-deployment.yaml
image: localhost:5001/cloud-infra/cloud-infra-auth:latest
```

- [ ] **Step 2: Deploy to Kind**

Run: `kubectl apply -f manifests/base/`
Run: `kubectl get pods -w`
Expected: Pods start (Native images start in milliseconds).

- [ ] **Step 3: Verify logs and middleware connectivity**

Run: `kubectl logs -f [auth-pod-name]`
Expected: Logs show successful connection to Nacos at `nacos-service:8848`.

- [ ] **Step 4: Commit**

```bash
git add manifests/base/
git commit -m "deploy: update manifests to use native images from local registry"
```
