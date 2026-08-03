#!/usr/bin/env bash
# ============================================================
# SealedSecrets 密钥管理脚本
# 用于将 Cloud-Infra 平台的敏感配置加密为 SealedSecret
#
# 前置条件:
#   - kubectl 可访问目标集群
#   - kubeseal CLI 已安装 (brew install kubeseal)
#   - sealed-secrets 控制器已部署
#
# 用法:
#   ./seal-secrets.sh              # 交互式生成所有密钥
#   ./seal-secrets.sh --dry-run    # 仅显示命令不执行
#   ./seal-secrets.sh --apply      # 直接应用到集群
# ============================================================
set -euo pipefail

NAMESPACE="${NAMESPACE:-cloud-infra}"
SEALED_SECRET_NAME="${SEALED_SECRET_NAME:-cloud-infra-sealed-secrets}"
DRY_RUN=false
APPLY=false

# 解析参数
for arg in "$@"; do
  case $arg in
    --dry-run) DRY_RUN=true ;;
    --apply) APPLY=true ;;
  esac
done

# 检查工具
command -v kubeseal >/dev/null 2>&1 || { echo "错误: kubeseal 未安装"; exit 1; }
command -v kubectl >/dev/null 2>&1 || { echo "错误: kubectl 未安装"; exit 1; }

# 检查控制器
if ! kubectl get deployment -n kube-system sealed-secrets-controller >/dev/null 2>&1; then
  echo "警告: sealed-secrets 控制器未检测到，请先部署:"
  echo "  kubectl apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.27.1/controller.yaml"
  if [ "$DRY_RUN" = false ]; then
    read -r -p "是否继续 (y/N)? " continue_install
    [[ "$continue_install" =~ ^[Yy]$ ]] || exit 0
  fi
fi

# 定义要加密的密钥
declare -A SECRETS=(
  [postgres.password]=""
  [jwt.secret]=""
  [minio.access-key]=""
  [minio.secret-key]=""
  [rabbitmq.password]=""
  [redis.password]=""
  [encryption.key]=""
)

echo "=========================================="
echo "Cloud-Infra SealedSecrets 密钥加密"
echo "Namespace: ${NAMESPACE}"
echo "SealedSecret: ${SEALED_SECRET_NAME}"
echo "=========================================="

# 生成密钥值
generate_secret_value() {
  local key="$1"
  local value=""
  
  case $key in
    postgres.password)
      value="${POSTGRES_PASSWORD:-$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)}"
      ;;
    jwt.secret)
      value="${JWT_SECRET:-$(openssl rand -base64 32 | tr -d '/+=' | head -c 32)}"
      ;;
    minio.access-key)
      value="${MINIO_ACCESS_KEY:-minioadmin}"
      ;;
    minio.secret-key)
      value="${MINIO_SECRET_KEY:-$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)}"
      ;;
    rabbitmq.password)
      value="${RABBITMQ_PASSWORD:-$(openssl rand -base64 16 | tr -d '/+=' | head -c 16)}"
      ;;
    redis.password)
      value="${REDIS_PASSWORD:-$(openssl rand -base64 16 | tr -d '/+=' | head -c 16)}"
      ;;
    encryption.key)
      value="${ENCRYPTION_KEY:-$(openssl rand -base64 32)}"
      ;;
  esac
  
  echo "$value"
}

# 构建加密数据
ENCRYPTED_YAML=""
for key in "${!SECRETS[@]}"; do
  value=$(generate_secret_value "$key")
  echo "🔐 加密密钥: $key"
  
  if [ "$DRY_RUN" = true ]; then
    echo "  (dry-run) 值: $value"
    continue
  fi
  
  ENCRYPTED=$(echo -n "$value" | kubeseal --raw --name "$SEALED_SECRET_NAME" --namespace "$NAMESPACE" 2>/dev/null)
  if [ -z "$ENCRYPTED" ]; then
    echo "  ⚠️ 加密失败，跳过 $key"
    continue
  fi
  ENCRYPTED_YAML="${ENCRYPTED_YAML}    ${key}: \"${ENCRYPTED}\"\n"
done

# 输出 SealedSecret YAML
if [ -n "$ENCRYPTED_YAML" ] || [ "$DRY_RUN" = true ]; then
  cat <<EOF
---
apiVersion: bitnami.com/v1alpha1
kind: SealedSecret
metadata:
  name: ${SEALED_SECRET_NAME}
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/part-of: cloud-infra
    sealed-secrets: enabled
spec:
  template:
    metadata:
      name: ${SEALED_SECRET_NAME}
      namespace: ${NAMESPACE}
    type: Opaque
  encryptedData:
$(echo -e "$ENCRYPTED_YAML")
EOF
fi

# 应用
if [ "$APPLY" = true ] && [ "$DRY_RUN" = false ]; then
  echo "应用 SealedSecret 到集群..."
  kubectl apply -f - <<EOF
apiVersion: bitnami.com/v1alpha1
kind: SealedSecret
metadata:
  name: ${SEALED_SECRET_NAME}
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/part-of: cloud-infra
    sealed-secrets: enabled
spec:
  template:
    metadata:
      name: ${SEALED_SECRET_NAME}
      namespace: ${NAMESPACE}
    type: Opaque
  encryptedData:
$(echo -e "$ENCRYPTED_YAML")
EOF
  echo "✅ SealedSecret 已应用: ${SEALED_SECRET_NAME}"
fi

echo ""
echo "✅ 完成! SealedSecret 控制器将解密并创建 Secret: ${SEALED_SECRET_NAME}"
echo "提示: 应用可通过 envFrom 引用此 Secret 或挂载为卷"
