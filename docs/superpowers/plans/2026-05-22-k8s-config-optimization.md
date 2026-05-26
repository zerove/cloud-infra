# K8s 配置优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 优化 K8s 配置，实现配置集中化、增加健康检查与资源限制，并改进部署脚本。

**Architecture:** 采用 Kubernetes 原生对象进行标准化。通过 ConfigMap 管理共享环境变量，Deployment 中增加 Liveness/Readiness 探针及 Resources 定义。

**Tech Stack:** Kubernetes (K3s), Spring Boot Actuator, Bash.

---

### Task 1: 更新 ConfigMap

**Files:**
- Modify: `manifests/base/configmap.yaml`

- [ ] **Step 1: 修改 ConfigMap 内容**
  增加 `JAVA_OPTS` 以支持虚拟线程及内存限制。

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: cloud-infra-config
data:
  SPRING_CLOUD_NACOS_SERVER_ADDR: "nacos-service:8848"
  JAVA_OPTS: "-XX:+UseZGC -XX:+UseVirtualThreads -Xms256m -Xmx512m"
```

- [ ] **Step 2: 提交更改**

```bash
git add manifests/base/configmap.yaml
git commit -m "chore(k8s): centralize config and add JAVA_OPTS in ConfigMap"
```

---

### Task 2: 标准化 Gateway 服务 Deployment

**Files:**
- Modify: `manifests/base/gateway-deployment.yaml`

- [ ] **Step 1: 更新 Deployment 定义**
  应用 `envFrom`，添加资源限制及探针。

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: gateway-service
spec:
  replicas: 1
  selector:
    matchLabels:
      app: gateway
  template:
    metadata:
      labels:
        app: gateway
    spec:
      containers:
      - name: gateway
        image: localhost/cloud-infra-gateway:prod
        envFrom:
        - configMapRef:
            name: cloud-infra-config
        ports:
        - containerPort: 9000
        resources:
          requests:
            cpu: 100m
            memory: 256Mi
          limits:
            cpu: 500m
            memory: 512Mi
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 9000
          initialDelaySeconds: 15
          periodSeconds: 10
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 9000
          initialDelaySeconds: 30
          periodSeconds: 20
---
apiVersion: v1
kind: Service
metadata:
  name: gateway-service
spec:
  type: NodePort
  ports:
  - port: 9000
    targetPort: 9000
    nodePort: 30000
  selector:
    app: gateway
```

- [ ] **Step 2: 提交更改**

```bash
git add manifests/base/gateway-deployment.yaml
git commit -m "refactor(k8s): standardize gateway-service deployment"
```

---

### Task 3: 标准化 Auth 服务 Deployment

**Files:**
- Modify: `manifests/base/auth-deployment.yaml`

- [ ] **Step 1: 更新 Deployment 定义**
  切换到 `envFrom`，添加资源限制及探针。注意 Auth 使用 native 镜像，启动速度快。

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: auth-service
spec:
  replicas: 1
  selector:
    matchLabels:
      app: auth
  template:
    metadata:
      labels:
        app: auth
    spec:
      containers:
      - name: auth
        image: localhost/cloud-infra-auth:native
        envFrom:
        - configMapRef:
            name: cloud-infra-config
        ports:
        - containerPort: 9200
        resources:
          requests:
            cpu: 50m
            memory: 64Mi
          limits:
            cpu: 200m
            memory: 128Mi
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 9200
          initialDelaySeconds: 5
          periodSeconds: 5
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 9200
          initialDelaySeconds: 10
          periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: auth-service
spec:
  ports:
  - port: 9200
    targetPort: 9200
  selector:
    app: auth
```

- [ ] **Step 2: 提交更改**

```bash
git add manifests/base/auth-deployment.yaml
git commit -m "refactor(k8s): standardize auth-service deployment"
```

---

### Task 4: 标准化 System 服务 Deployment

**Files:**
- Modify: `manifests/base/system-deployment.yaml`

- [ ] **Step 1: 更新 Deployment 定义**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: system-service
spec:
  replicas: 1
  selector:
    matchLabels:
      app: system
  template:
    metadata:
      labels:
        app: system
    spec:
      containers:
      - name: system
        image: localhost/cloud-infra-system:jre
        envFrom:
        - configMapRef:
            name: cloud-infra-config
        ports:
        - containerPort: 9300
        resources:
          requests:
            cpu: 100m
            memory: 256Mi
          limits:
            cpu: 500m
            memory: 512Mi
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 9300
          initialDelaySeconds: 20
          periodSeconds: 10
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 9300
          initialDelaySeconds: 40
          periodSeconds: 20
---
apiVersion: v1
kind: Service
metadata:
  name: system-service
spec:
  ports:
  - port: 9300
    targetPort: 9300
  selector:
    app: system
```

- [ ] **Step 2: 提交更改**

```bash
git add manifests/base/system-deployment.yaml
git commit -m "refactor(k8s): standardize system-service deployment"
```

---

### Task 5: 标准化 Notification 服务 Deployment

**Files:**
- Modify: `manifests/base/notification-deployment.yaml`

- [ ] **Step 1: 更新 Deployment 定义**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: notification-service
spec:
  replicas: 1
  selector:
    matchLabels:
      app: notification
  template:
    metadata:
      labels:
        app: notification
    spec:
      containers:
      - name: notification
        image: localhost/cloud-infra-notification:jre
        envFrom:
        - configMapRef:
            name: cloud-infra-config
        ports:
        - containerPort: 9500
        resources:
          requests:
            cpu: 100m
            memory: 256Mi
          limits:
            cpu: 500m
            memory: 512Mi
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 9500
          initialDelaySeconds: 20
          periodSeconds: 10
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 9500
          initialDelaySeconds: 40
          periodSeconds: 20
---
apiVersion: v1
kind: Service
metadata:
  name: notification-service
spec:
  ports:
  - port: 9500
    targetPort: 9500
  selector:
    app: notification
```

- [ ] **Step 2: 提交更改**

```bash
git add manifests/base/notification-deployment.yaml
git commit -m "refactor(k8s): standardize notification-service deployment"
```

---

### Task 6: 标准化 Search 服务 Deployment

**Files:**
- Modify: `manifests/base/search-deployment.yaml`

- [ ] **Step 1: 更新 Deployment 定义**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: search-service
spec:
  replicas: 1
  selector:
    matchLabels:
      app: search
  template:
    metadata:
      labels:
        app: search
    spec:
      containers:
      - name: search
        image: localhost/cloud-infra-search:jre
        envFrom:
        - configMapRef:
            name: cloud-infra-config
        ports:
        - containerPort: 9400
        resources:
          requests:
            cpu: 100m
            memory: 256Mi
          limits:
            cpu: 500m
            memory: 512Mi
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 9400
          initialDelaySeconds: 20
          periodSeconds: 10
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 9400
          initialDelaySeconds: 40
          periodSeconds: 20
---
apiVersion: v1
kind: Service
metadata:
  name: search-service
spec:
  ports:
  - port: 9400
    targetPort: 9400
  selector:
    app: search
```

- [ ] **Step 2: 提交更改**

```bash
git add manifests/base/search-deployment.yaml
git commit -m "refactor(k8s): standardize search-service deployment"
```

---

### Task 7: 优化部署脚本

**Files:**
- Modify: `deploy-k8s.sh`

- [ ] **Step 1: 重构脚本内容**

```bash
#!/bin/bash
set -e

echo ">>> 正在提交 Kubernetes 配置至集群..."
kubectl apply -f manifests/base/

echo ">>> 等待服务就绪..."
kubectl wait --for=condition=ready pod -l "app in (gateway, auth, system, notification, search)" --timeout=300s

echo ">>> 所有业务服务已成功启动且就绪。"
kubectl get pods
```

- [ ] **Step 2: 提交更改**

```bash
git add deploy-k8s.sh
git commit -m "refactor: optimize deployment script with kubectl wait"
```
