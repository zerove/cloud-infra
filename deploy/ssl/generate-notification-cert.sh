#!/usr/bin/env bash
# 开发证书，生产环境必须使用受信任 CA 签发的证书。
set -euo pipefail
umask 077
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERT_DIR="${SSL_OUTPUT_DIR:-${SCRIPT_DIR}/notification}"
: "${SSL_KEYSTORE_PASSWORD:?必须提供 SSL_KEYSTORE_PASSWORD，至少 6 个字符}"
if ((${#SSL_KEYSTORE_PASSWORD} < 6)); then
  echo "SSL_KEYSTORE_PASSWORD 至少需要 6 个字符" >&2
  exit 1
fi
ALIAS="${SSL_KEY_ALIAS:-notification}"
CN="${SSL_CN:-notification.local}"
SAN="${SSL_SAN:-dns:notification.local,dns:localhost,dns:notification,ip:127.0.0.1}"
mkdir -p "${CERT_DIR}"
for file in notification.p12 server.crt; do
  if [[ -e "${CERT_DIR}/${file}" ]]; then
    echo "拒绝覆盖已有证书: ${CERT_DIR}/${file}" >&2
    exit 1
  fi
done
keytool -genkeypair -noprompt -alias "${ALIAS}" -keyalg RSA -keysize 2048 \
  -validity 365 -dname "CN=${CN},OU=Notification,O=CloudInfra,C=CN" \
  -ext "SAN=${SAN}" -ext "EKU=serverAuth" -storetype PKCS12 \
  -keystore "${CERT_DIR}/notification.p12" -storepass:env SSL_KEYSTORE_PASSWORD
keytool -exportcert -rfc -alias "${ALIAS}" \
  -keystore "${CERT_DIR}/notification.p12" -storepass:env SSL_KEYSTORE_PASSWORD \
  -file "${CERT_DIR}/server.crt"
chmod 600 "${CERT_DIR}/notification.p12"
chmod 644 "${CERT_DIR}/server.crt"
printf '证书已生成: %s\n密钥库密码不会打印，请从环境变量注入。\n' "${CERT_DIR}"
