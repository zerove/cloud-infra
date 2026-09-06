#!/usr/bin/env bash
# 生成 notification-service 自签 TLS 证书 (开发/演示用)
# 生产环境应使用 Let's Encrypt 或企业 CA 签发的证书
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/../deploy/ssl"
CERT_DIR="${OUTPUT_DIR}/notification"
PASSWORD="${SSL_KEYSTORE_PASSWORD:-changeit}"
ALIAS="${SSL_KEY_ALIAS:-notification}"
DAYS=365
CN="${SSL_CN:-notification.local}"
SAN="${SSL_SAN:-dns:notification.local,dns:localhost,ip:127.0.0.1}"

mkdir -p "${CERT_DIR}"
cd "${CERT_DIR}"

echo "==> 生成 notification-service 自签证书 (CN=${CN}, SAN=${SAN})"

# 1. 生成私钥 (RSA 2048)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out server.key

# 2. 生成证书签名请求 (CSR)
openssl req -new -key server.key -out server.csr \
  -subj "/C=CN/ST=Beijing/L=Beijing/O=CloudInfra/OU=Notification/CN=${CN}"

# 3. 用 v3 SAN 扩展签发证书
cat > server.ext <<EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=${SAN}
EOF

openssl x509 -req -in server.csr -signkey server.key -CAcreateserial \
  -out server.crt -days ${DAYS} -extfile server.ext

# 4. 转 PKCS12 keystore (Spring Boot server.ssl.key-store 格式)
openssl pkcs12 -export -in server.crt -inkey server.key \
  -name "${ALIAS}" -out notification.p12 -password pass:"${PASSWORD}"

# 5. 清理中间文件 (保留 .p12 + .key + .crt)
rm -f server.csr server.ext server.srl

# 6. 设置权限
chmod 600 server.key notification.p12
chmod 644 server.crt

echo ""
echo "==> 证书生成完成:"
ls -la "${CERT_DIR}"
echo ""
echo "用法 (Nginx 终止 TLS):"
echo "  ssl_certificate     ${CERT_DIR}/server.crt;"
echo "  ssl_certificate_key ${CERT_DIR}/server.key;"
echo ""
echo "用法 (Spring Boot 直接终止 TLS):"
echo "  SSL_KEYSTORE_PATH=${CERT_DIR}/notification.p12"
echo "  SSL_KEYSTORE_PASSWORD=${PASSWORD}"
echo "  SSL_KEY_ALIAS=${ALIAS}"
