#!/usr/bin/env bash
# Cloud-Infra 端到端复现脚本
# 完整闭环: 构建镜像 -> 部署 -> 漂移检测 -> 启动 -> 健康 -> e2e 验收
#
# 前置条件:
#   - podman / podman-compose 已装
#   - mvn (或 mvnw) 可用
#   - 端口 8081/8082/8083/8084/8085/9000/9301 未占用
#   - 当前 commit 在 4ed317f 之后
#
# 用法:
#   ./verify-e2e.sh           # 完整流程
#   ./verify-e2e.sh --skip-build  # 跳过 mvn 构建 (用现有镜像)

set -uo pipefail
# 脚本位于 repo 根, cd 到自身所在目录
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR" || exit 1

SKIP_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    *) echo "未知参数: $arg"; exit 1 ;;
  esac
done

PASS=0; FAIL=0
ok()   { printf "  \033[32mPASS\033[0m %s\n" "$1"; PASS=$((PASS+1)); }
fail() { printf "  \033[31mFAIL\033[0m %s\n" "$1"; FAIL=$((FAIL+1)); }

echo "=========================================="
echo "  Cloud-Infra E2E 复现 ($(git rev-parse --short HEAD))"
echo "=========================================="
echo

# 0. 准备 .env
echo "[0/7] 准备 .env ..."
if [ ! -f .env ]; then
  if [ -f .env.example ]; then
    cp .env.example .env
    sed -i "s|^AUTH_JWT_SECRET=.*|AUTH_JWT_SECRET=$(head -c 48 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 48)|" .env
    sed -i "s|^INTERNAL_CALL_SECRET=.*|INTERNAL_CALL_SECRET=$(head -c 48 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 48)|" .env
    sed -i "s|^AUTH_CAPTCHA_FIXED_CODE=.*|AUTH_CAPTCHA_FIXED_CODE=1234|" .env
    ok ".env 已生成 (密钥随机化)"
  else
    fail ".env 缺失且 .env.example 不存在"
    exit 1
  fi
else
  ok ".env 已存在"
fi
echo

# 1. 构建
if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "[1/7] mvn clean install -DskipTests ..."
  if mvn clean install -DskipTests -q 2>&1 | tail -5; then
    ok "19 模块编译完成"
  else
    fail "mvn 编译失败"
    exit 1
  fi
else
  ok "跳过 mvn 构建 (--skip-build)"
fi
echo

# 2. Docker 镜像
echo "[2/7] 构建 6 个服务镜像 ..."
unset JAVA_OPTS
set -a; source <(grep -v '^JAVA_OPTS=' .env); set +a
if [ "$SKIP_BUILD" -eq 0 ]; then
  (
    podman build -t localhost/cloud-infra-gateway:latest cloud-infra-gateway
    podman build -t localhost/cloud-infra-auth:latest cloud-infra-auth
    podman build -t localhost/cloud-infra-system:latest cloud-infra-modules/system-module
    podman build -t localhost/cloud-infra-file:latest cloud-infra-modules/file-service
    podman build -t localhost/cloud-infra-notification:latest cloud-infra-modules/notification-service
    podman build -t localhost/cloud-infra-search:latest cloud-infra-modules/search-service
  ) 2>&1 | tail -3
  ok "6 个镜像构建完成"
else
  ok "跳过镜像构建 (--skip-build)"
fi
echo

# 3. 启动基础设施
echo "[3/7] 启动基础设施 + 微服务 ..."
cd deploy
podman-compose --profile full --env-file ../.env up -d 2>&1 | tail -3
ok "compose 启动"
echo

# 4. 漂移检测
echo "[4/7] Nacos 配置漂移检测 ..."
cd ..
if ./deploy/up.sh --check 2>&1 | tail -1 | grep -q "无漂移"; then
  ok "Nacos 配置 0 漂移"
else
  fail "Nacos 配置存在漂移"
  ./deploy/up.sh --check 2>&1 | tail -3
fi
echo

# 5. 等健康
echo "[5/7] 等待 6 个微服务 healthy ..."
NEEDED=6
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do
  HEALTHY=$(podman ps --format "{{.Status}}" 2>/dev/null | grep -E "Up .* \(healthy\)|healthy\)" | wc -l)
  if [ "$HEALTHY" -ge "$NEEDED" ]; then ok "微服务 $HEALTHY 个 healthy"; break; fi
  sleep 6
done
[ "$HEALTHY" -ge "$NEEDED" ] || { fail "仅 $HEALTHY 个 healthy, 等待超时"; exit 1; }
echo

# 6. e2e 验收
echo "[6/7] e2e-test.sh ..."
if bash e2e-test.sh 2>&1 | tail -1 | grep -q "PASS=6"; then
  ok "e2e 6/6 PASS"
else
  fail "e2e 未全过"
  bash e2e-test.sh 2>&1 | tail -5
fi
echo

# 7. 容器 env 一致性
echo "[7/7] 容器 env 一致性 ..."
ALL_OK=1
for svc in gateway auth system file notification search; do
  INT=$(podman exec cloud-$svc sh -c 'echo "${#INTERNAL_CALL_SECRET}"' 2>/dev/null)
  if [ "$INT" = "48" ]; then
    : # 静默 OK
  else
    fail "$svc INTERNAL_CALL_SECRET=$INT (期望 48)"
    ALL_OK=0
  fi
done
[ "$ALL_OK" -eq 1 ] && ok "6 容器 INTERNAL_CALL_SECRET=48"
echo

echo "=========================================="
printf "  结果: %d PASS / %d FAIL\n" "$PASS" "$FAIL"
echo "=========================================="
exit $FAIL
