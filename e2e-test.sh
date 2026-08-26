#!/usr/bin/env bash
set -euo pipefail

# E2E 真实链路验收：登录 -> 受保护资源 -> 401 -> 403
# 前置条件：本地环境已启动（./deploy/up.sh），且 auth 启用固定验证码
#   （deploy/config/auth.yaml 或环境变量 AUTH_CAPTCHA_FIXED_CODE=1234）
GATEWAY="${GATEWAY:-http://localhost:9000}"
FIXED_CODE="${AUTH_CAPTCHA_FIXED_CODE:-1234}"
PASS=0; FAIL=0

# 本机透明/环境代理会劫持回环请求(SYN_SENT 挂起), 全链路强制直连
export no_proxy="*" NO_PROXY="*"
unset http_proxy https_proxy all_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY
CURL="curl -s --noproxy *"

expect_contains() { # desc haystack needle
  local desc="$1" hay="$2" needle="$3"
  if echo "$hay" | grep -q "$needle"; then PASS=$((PASS+1)); echo "  PASS: $desc";
  else FAIL=$((FAIL+1)); echo "  FAIL: $desc (期望含 '$needle'，实际: ${hay:0:200})"; fi
}

echo "=== E2E 验收测试开始 ==="

echo "1. 获取验证码 uuid..."
CAPTCHA_RESP=$(curl -s -f --noproxy '*' -X GET "$GATEWAY/auth/captchaImage")
UUID=$(echo "$CAPTCHA_RESP" | grep -o '"uuid":"[^"]*"' | cut -d'"' -f4)
[ -n "$UUID" ] || { echo "FAIL: 未获取到 captcha uuid"; exit 1; }
echo "  UUID: $UUID"

echo "2. 场景一：错误密码登录应被拒绝..."
RESP=$(curl -s --noproxy '*' -X POST "$GATEWAY/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"wrong-password","code":"'"$FIXED_CODE"'","uuid":"'"$UUID"'"}')
expect_contains "错误密码返回失败码" "$RESP" '"code":-\?[0-9]*' 
if echo "$RESP" | grep -q '"token"'; then
  FAIL=$((FAIL+1)); echo "  FAIL: 错误密码竟拿到 token"
else
  PASS=$((PASS+1)); echo "  PASS: 错误密码未签发 token"
fi

echo "3. 场景二：正确凭据登录获取 token..."
CAPTCHA_RESP=$(curl -s -f --noproxy '*' -X GET "$GATEWAY/auth/captchaImage")
UUID=$(echo "$CAPTCHA_RESP" | grep -o '"uuid":"[^"]*"' | cut -d'"' -f4)
LOGIN_RESP=$(curl -s --noproxy '*' -X POST "$GATEWAY/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","code":"'"$FIXED_CODE"'","uuid":"'"$UUID"'"}')
TOKEN=$(echo "$LOGIN_RESP" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [ -n "$TOKEN" ]; then PASS=$((PASS+1)); echo "  PASS: 登录成功获得 token";
else FAIL=$((FAIL+1)); echo "  FAIL: 登录未获得 token，响应: $LOGIN_RESP"; fi

echo "4. 场景三：无 token 访问受保护资源应 401..."
HTTP_CODE=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" "$GATEWAY/system/user/list")
if [ "$HTTP_CODE" = "401" ]; then PASS=$((PASS+1)); echo "  PASS: 无 token -> 401";
else FAIL=$((FAIL+1)); echo "  FAIL: 期望 401 实际 $HTTP_CODE"; fi

echo "5. 场景四：带 token 访问受保护资源应 2xx..."
HTTP_CODE=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" "$GATEWAY/system/user/list")
if [[ "$HTTP_CODE" == 2* ]]; then PASS=$((PASS+1)); echo "  PASS: 带 token -> $HTTP_CODE";
else FAIL=$((FAIL+1)); echo "  FAIL: 期望 2xx 实际 $HTTP_CODE"; fi

echo "6. 场景五：伪造 token 应 401..."
HTTP_CODE=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -H "Authorization: Bearer forged.token.value" "$GATEWAY/system/user/list")
if [ "$HTTP_CODE" = "401" ]; then PASS=$((PASS+1)); echo "  PASS: 伪造 token -> 401";
else FAIL=$((FAIL+1)); echo "  FAIL: 期望 401 实际 $HTTP_CODE"; fi

echo "=== 结果: PASS=$PASS FAIL=$FAIL ==="
[ "$FAIL" -eq 0 ]
