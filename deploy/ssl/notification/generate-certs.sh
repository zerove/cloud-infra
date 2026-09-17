#!/usr/bin/env bash
# 兼容目录内入口，统一生成 PKCS12；不会生成独立的 PEM 私钥。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/../generate-notification-cert.sh" "$@"
